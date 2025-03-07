package com.swmansion.rnscreens

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.facebook.react.ReactRootView
import com.facebook.react.modules.core.ChoreographerCompat
import com.facebook.react.modules.core.ReactChoreographer
import com.swmansion.rnscreens.Screen.ActivityState
import java.lang.IllegalStateException

open class ScreenContainer<T : ScreenFragment>(context: Context?) : ViewGroup(context) {
    @JvmField
    protected val mScreenFragments = ArrayList<T>()
    @JvmField
    protected var mFragmentManager: FragmentManager? = null
    private var mCurrentTransaction: FragmentTransaction? = null
    private var mProcessingTransaction: FragmentTransaction? = null
    private var mNeedUpdate = false
    private var mIsAttached = false
    private val mFrameCallback: ChoreographerCompat.FrameCallback = object : ChoreographerCompat.FrameCallback() {
        override fun doFrame(frameTimeNanos: Long) {
            updateIfNeeded()
        }
    }
    private var mLayoutEnqueued = false
    private val mLayoutCallback: ChoreographerCompat.FrameCallback = object : ChoreographerCompat.FrameCallback() {
        override fun doFrame(frameTimeNanos: Long) {
            mLayoutEnqueued = false
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            layout(left, top, right, bottom)
        }
    }
    private var mParentScreenFragment: ScreenFragment? = null
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var i = 0
        val size = childCount
        while (i < size) {
            getChildAt(i).layout(0, 0, width, height)
            i++
        }
    }

    override fun removeView(view: View) {
        // The below block is a workaround for an issue with keyboard handling within fragments. Despite
        // Android handles input focus on the fragments that leave the screen, the keyboard stays open
        // in a number of cases. The issue can be best reproduced on Android 5 devices, before some
        // changes in Android's InputMethodManager have been introduced (specifically around dismissing
        // the keyboard in onDetachedFromWindow). However, we also noticed the keyboard issue happen
        // intermittently on recent versions of Android as well. The issue hasn't been previously
        // noticed as in React Native <= 0.61 there was a logic that'd trigger keyboard dismiss upon a
        // blur event (the blur even gets dispatched properly, the keyboard just stays open despite
        // that) – note the change in RN core here:
        // https://github.com/facebook/react-native/commit/e9b4928311513d3cbbd9d875827694eab6cfa932
        // The workaround is to force-hide keyboard when the screen that has focus is dismissed (we
        // detect that in removeView as super.removeView causes the input view to un focus while keeping
        // the keyboard open).
        if (view === focusedChild) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
        }
        super.removeView(view)
    }

    override fun requestLayout() {
        super.requestLayout()
        @Suppress("SENSELESS_COMPARISON") // mLayoutCallback can be null here since this method can be called in init
        if (!mLayoutEnqueued && mLayoutCallback != null) {
            mLayoutEnqueued = true
            // we use NATIVE_ANIMATED_MODULE choreographer queue because it allows us to catch the current
            // looper loop instead of enqueueing the update in the next loop causing a one frame delay.
            ReactChoreographer.getInstance()
                .postFrameCallback(
                    ReactChoreographer.CallbackType.NATIVE_ANIMATED_MODULE, mLayoutCallback
                )
        }
    }

    val isNested: Boolean
        get() = mParentScreenFragment != null

    protected fun markUpdated() {
        if (!mNeedUpdate) {
            mNeedUpdate = true
            // enqueue callback of NATIVE_ANIMATED_MODULE type as all view operations are executed in
            // DISPATCH_UI type and we want the callback to be called right after in the same frame.
            ReactChoreographer.getInstance()
                .postFrameCallback(
                    ReactChoreographer.CallbackType.NATIVE_ANIMATED_MODULE, mFrameCallback
                )
        }
    }

    fun notifyChildUpdate() {
        markUpdated()
    }

    protected open fun adapt(screen: Screen): T {
        @Suppress("UNCHECKED_CAST")
        return ScreenFragment(screen) as T
    }

    fun addScreen(screen: Screen, index: Int) {
        val fragment = adapt(screen)
        screen.fragment = fragment
        mScreenFragments.add(index, fragment)
        screen.container = this
        markUpdated()
    }

    open fun removeScreenAt(index: Int) {
        mScreenFragments[index].screen.container = null
        mScreenFragments.removeAt(index)
        markUpdated()
    }

    open fun removeAllScreens() {
        for (screenFragment in mScreenFragments) {
            screenFragment.screen.container = null
        }
        mScreenFragments.clear()
        markUpdated()
    }

    val screenCount: Int
        get() = mScreenFragments.size

    fun getScreenAt(index: Int): Screen {
        return mScreenFragments[index].screen
    }

    open val topScreen: Screen?
        get() {
            for (screenFragment in mScreenFragments) {
                if (getActivityState(screenFragment) === ActivityState.ON_TOP) {
                    return screenFragment.screen
                }
            }
            return null
        }

    private fun setFragmentManager(fm: FragmentManager) {
        mFragmentManager = fm
        updateIfNeeded()
    }

    private fun setupFragmentManager() {
        var parent: ViewParent = this
        // We traverse view hierarchy up until we find screen parent or a root view
        while (!(parent is ReactRootView || parent is Screen) &&
            parent.parent != null
        ) {
            parent = parent.parent
        }
        // If parent is of type Screen it means we are inside a nested fragment structure.
        // Otherwise we expect to connect directly with root view and get root fragment manager
        if (parent is Screen) {
            val screenFragment = parent.fragment
            check(screenFragment != null) { "Parent Screen does not have its Fragment attached" }
            mParentScreenFragment = screenFragment
            screenFragment.registerChildScreenContainer(this)
            setFragmentManager(screenFragment.childFragmentManager)
            return
        }

        // we expect top level view to be of type ReactRootView, this isn't really necessary but in
        // order to find root view we test if parent is null. This could potentially happen also when
        // the view is detached from the hierarchy and that test would not correctly indicate the root
        // view. So in order to make sure we indeed reached the root we test if it is of a correct type.
        // This allows us to provide a more descriptive error message for the aforementioned case.
        check(parent is ReactRootView) { "ScreenContainer is not attached under ReactRootView" }
        // ReactRootView is expected to be initialized with the main React Activity as a context but
        // in case of Expo the activity is wrapped in ContextWrapper and we need to unwrap it
        var context = parent.context
        while (context !is FragmentActivity && context is ContextWrapper) {
            context = context.baseContext
        }
        check(context is FragmentActivity) { "In order to use RNScreens components your app's activity need to extend ReactFragmentActivity or ReactCompatActivity" }
        setFragmentManager(context.supportFragmentManager)
    }

    protected fun getOrCreateTransaction(): FragmentTransaction {
        if (mCurrentTransaction == null) {
            val fragmentManager = requireNotNull(mFragmentManager, { "mFragmentManager is null when creating transaction" })
            val transaction = fragmentManager.beginTransaction()
            transaction.setReorderingAllowed(true)
            mCurrentTransaction = transaction
        }
        mCurrentTransaction?.let { return it }
        throw IllegalStateException("mCurrentTransaction changed to null during creating transaction")
    }

    protected fun tryCommitTransaction() {
        val transaction = mCurrentTransaction
        if (transaction != null) {
            mProcessingTransaction = transaction
            mProcessingTransaction?.runOnCommit {
                if (mProcessingTransaction === transaction) {
                    // we need to take into account that commit is initiated with some other transaction
                    // while the previous one is still processing. In this case mProcessingTransaction
                    // gets overwritten and we don't want to set it to null until the second transaction
                    // is finished.
                    mProcessingTransaction = null
                }
            }
            transaction.commitAllowingStateLoss()
            mCurrentTransaction = null
        }
    }

    private fun attachScreen(screenFragment: T) {
        getOrCreateTransaction().add(id, screenFragment)
    }

    private fun moveToFront(screenFragment: ScreenFragment) {
        val transaction = getOrCreateTransaction()
        transaction.remove(screenFragment)
        transaction.add(id, screenFragment)
    }

    private fun detachScreen(screenFragment: ScreenFragment) {
        getOrCreateTransaction().remove(screenFragment)
    }

    private fun getActivityState(screenFragment: ScreenFragment): ActivityState? {
        return screenFragment.screen.activityState
    }

    open fun hasScreen(screenFragment: ScreenFragment?): Boolean {
        return mScreenFragments.contains(screenFragment)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mIsAttached = true
        mNeedUpdate = true
        setupFragmentManager()
    }

    /** Removes fragments from fragment manager that are attached to this container  */
    private fun removeMyFragments(fragmentManager: FragmentManager) {
        val transaction = fragmentManager.beginTransaction()
        var hasFragments = false
        for (fragment in fragmentManager.fragments) {
            if (fragment is ScreenFragment &&
                fragment.screen.container === this
            ) {
                transaction.remove(fragment)
                hasFragments = true
            }
        }

        if (hasFragments) {
            transaction.commitNowAllowingStateLoss()
        }
    }

    override fun onDetachedFromWindow() {
        // if there are pending transactions and this view is about to get detached we need to perform
        // them here as otherwise fragment manager will crash because it won't be able to find container
        // view. We also need to make sure all the fragments attached to the given container are removed
        // from fragment manager as in some cases fragment manager may be reused and in such case it'd
        // attempt to reattach previously registered fragments that are not removed
        mFragmentManager?.let {
            if (!it.isDestroyed) {
                removeMyFragments(it)
                it.executePendingTransactions()
            }
        }

        mParentScreenFragment?.unregisterChildScreenContainer(this)
        mParentScreenFragment = null

        super.onDetachedFromWindow()
        mIsAttached = false
        // When fragment container view is detached we force all its children to be removed.
        // It is because children screens are controlled by their fragments, which can often have a
        // delayed lifecycle (due to transitions). As a result due to ongoing transitions the fragment
        // may choose not to remove the view despite the parent container being completely detached
        // from the view hierarchy until the transition is over. In such a case when the container gets
        // re-attached while tre transition is ongoing, the child view would still be there and we'd
        // attempt to re-attach it to with a misconfigured fragment. This would result in a crash. To
        // avoid it we clear all the children here as we attach all the child fragments when the
        // container is reattached anyways. We don't use `removeAllViews` since it does not check if the
        // children are not already detached, which may lead to calling `onDetachedFromWindow` on them
        // twice.
        // We also get the size earlier, because we will be removing child views in `for` loop.
        val size = childCount
        for (i in size - 1 downTo 0) {
            removeViewAt(i)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        var i = 0
        val size = childCount
        while (i < size) {
            getChildAt(i).measure(widthMeasureSpec, heightMeasureSpec)
            i++
        }
    }

    private fun updateIfNeeded() {
        if (!mNeedUpdate || !mIsAttached || mFragmentManager == null) {
            return
        }
        mNeedUpdate = false
        onUpdate()
    }

    private fun onUpdate() {
        // We double check if fragment manager have any pending transactions to run.
        // In performUpdate we often check whether some fragments are added to
        // manager to avoid adding them for the second time (which result in crash).
        // By design performUpdate should be called at most once per frame, so this
        // should never happen, but in case there are some pending transaction we
        // need to flush them here such that Fragment#isAdded checks reflect the
        // reality and that we don't have enqueued fragment add commands that will
        // execute shortly and cause "Fragment already added" crash.
        mFragmentManager?.executePendingTransactions()
        performUpdate()
        notifyContainerUpdate()
    }

    protected open fun performUpdate() {
        // detach screens that are no longer active
        val orphaned: MutableSet<Fragment> = HashSet(requireNotNull(mFragmentManager, { "mFragmentManager is null when performing update in ScreenContainer" }).fragments)
        for (screenFragment in mScreenFragments) {
            if (getActivityState(screenFragment) === ActivityState.INACTIVE &&
                screenFragment.isAdded
            ) {
                detachScreen(screenFragment)
            }
            orphaned.remove(screenFragment)
        }
        if (orphaned.isNotEmpty()) {
            val orphanedAry = orphaned.toTypedArray()
            for (fragment in orphanedAry) {
                if (fragment is ScreenFragment) {
                    if (fragment.screen.container == null) {
                        detachScreen(fragment)
                    }
                }
            }
        }
        var transitioning = true
        if (topScreen != null) {
            // if there is an "onTop" screen it means the transition has ended
            transitioning = false
        }

        // attach newly activated screens
        var addedBefore = false
        for (screenFragment in mScreenFragments) {
            val activityState = getActivityState(screenFragment)
            if (activityState !== ActivityState.INACTIVE && !screenFragment.isAdded) {
                addedBefore = true
                attachScreen(screenFragment)
            } else if (activityState !== ActivityState.INACTIVE && addedBefore) {
                moveToFront(screenFragment)
            }
            screenFragment.screen.setTransitioning(transitioning)
        }
        tryCommitTransaction()
    }

    protected open fun notifyContainerUpdate() {
        topScreen?.fragment?.onContainerUpdate()
    }
}
