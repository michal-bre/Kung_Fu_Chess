package org.example.view;

/**
 * Implemented by view components whose preferred size may need to shrink
 * further to fit the screen's real available content area. A component's
 * own best guess at "how much room will the window chrome take" (title bar,
 * borders, taskbar) is necessarily a guess made before the native window
 * peer exists - actual title bar height varies by OS, theme, and DPI
 * scaling. GameWindow calls constrainToContentArea after its first pack(),
 * once the real frame insets are known, giving the component a chance to
 * correct that guess with the true numbers - see GameWindow's class doc.
 */
public interface ScreenFittable {

    /**
     * Called with the actual maximum width/height (in pixels) this
     * component may occupy: the real screen work area minus this specific
     * frame's real insets. Implementations should shrink - never grow -
     * their preferred size to fit, and only do so if they don't already
     * fit within the given bounds.
     */
    void constrainToContentArea(int maxWidth, int maxHeight);
}
