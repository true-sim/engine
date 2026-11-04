package com.truesim.engine.demo;

import com.truesim.engine.input.ActionGroup;
import com.truesim.engine.input.Axis1Action;
import com.truesim.engine.input.Axis2Action;
import com.truesim.engine.input.BinaryAction;
import com.truesim.engine.input.Binding;
import com.truesim.engine.input.Controls;
import com.truesim.engine.input.Filters;
import com.truesim.engine.platform.Key;
import com.truesim.engine.platform.MouseButton;

/**
 * The gallery's own actions: the 3D page's orbit camera and the pages. The UI's, the scene interaction's and the
 * profiler's come with their plugins. Rebound in the settings file ({@code input.gallery.camera.orbit=...}).
 */
final class GalleryActions {

    static final ActionGroup GALLERY = ActionGroup.root("gallery");

    static final ActionGroup CAMERA = GALLERY.child("camera");
    /** Orbiting, radians this frame: the mouse moved with the right button held. */
    static final Axis2Action ORBIT = CAMERA.axis2("orbit", Binding.axes2d(Controls.MOUSE_X, Controls.MOUSE_Y)
        .with(Controls.mouse(MouseButton.RIGHT)).filter(Filters.SCALE, 0.005f));
    /** Panning, pixels this frame: the mouse moved with the middle button held. */
    static final Axis2Action PAN = CAMERA.axis2("pan", Binding.axes2d(Controls.MOUSE_X, Controls.MOUSE_Y)
        .with(Controls.mouse(MouseButton.MIDDLE)));
    /** Zooming: the wheel. */
    static final Axis1Action ZOOM = CAMERA.axis1("zoom", Binding.of(Controls.WHEEL_Y));
    /** Back to the starting view: Home. */
    static final BinaryAction HOME = CAMERA.binary("home", Binding.key(Key.HOME));

    static final ActionGroup PAGES = GALLERY.child("pages");
    /** The next page: Ctrl+Page Down. */
    static final BinaryAction NEXT_PAGE = PAGES.binary("next", Binding.chord(Controls.CONTROL,
        Controls.key(Key.PAGE_DOWN)));
    /** The page before: Ctrl+Page Up. */
    static final BinaryAction PREVIOUS_PAGE = PAGES.binary("previous", Binding.chord(Controls.CONTROL,
        Controls.key(Key.PAGE_UP)));

    private GalleryActions() {
    }
}
