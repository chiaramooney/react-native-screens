#include "pch.h"
#include "ScreenContainer.h"
#include "JSValueXaml.h"
#include "NativeModules.h"

namespace winrt {
using namespace Microsoft::ReactNative;
using namespace Windows::Foundation;
using namespace Windows::Foundation::Collections;
using namespace Windows::UI;
using namespace Windows::UI::Xaml;
using namespace Windows::UI::Xaml::Controls;
} // namespace winrt

namespace winrt::RNScreens::implementation {
ScreenContainer::ScreenContainer(
    winrt::Microsoft::ReactNative::IReactContext reactContext)
    : m_reactContext(reactContext),
      m_children(
          {winrt::single_threaded_vector<Windows::UI::Xaml::UIElement>()}) {}

void ScreenContainer::addScreen(Screen &screen, int64_t) {
  screen.setScreenContainer(this);
  auto uiElement = screen.try_as<UIElement>();
  if (!uiElement)
    return;
  m_children.Append(uiElement);
  Content(uiElement);
  updateVisualTree();
}

void ScreenContainer::removeAllChildren() {
  Content(nullptr);
  m_children.Clear();
}

void ScreenContainer::removeChildAt(int64_t index) {
  auto screen = m_children.GetAt(index).try_as<Screen>();
  m_children.RemoveAt(static_cast<uint32_t>(index));
  //screen.onUnloaded();
}

void ScreenContainer::replaceChild(
    winrt::Windows::UI::Xaml::UIElement oldChild,
    winrt::Windows::UI::Xaml::UIElement newChild) {
  uint32_t index;
  if (!m_children.IndexOf(oldChild, index))
    return;

  m_children.SetAt(index, newChild);
}

winrt::impl::com_ref<Screen> ScreenContainer::getTopScreen() {
  for (int i = 0; i < m_children.Size(); i++) {
    auto screen = m_children.GetAt(i).try_as<Screen>();
    if (screen->getActivityState() == ActivityState::ON_TOP) {
      return screen;
    }
  }
  return nullptr;
}

void ScreenContainer::updateVisualTree() {
  auto topScreen = getTopScreen();
  if (!topScreen) {
    return;
  }
  auto uiElement = topScreen.try_as<UIElement>();
  if (!uiElement) {
    return;
  }
  Content(uiElement);
  for (int i=0; i < m_children.Size(); i++) {
    auto screen = m_children.GetAt(i).try_as<Screen>();
    if (screen->getActivityState() == ActivityState::INACTIVE){
      this->removeChildAt(i);
      i--;
    }
  }
}
} // namespace winrt::RNScreens::implementation
