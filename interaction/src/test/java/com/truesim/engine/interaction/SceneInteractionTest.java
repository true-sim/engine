package com.truesim.engine.interaction;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.render.Camera;
import com.truesim.scene.core.CameraLens;
import com.truesim.scene.core.Hovered;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.Selected;
import com.truesim.scene.core.SpatialIndex;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneInteractionTest {

    /** A scene seen from the origin down -Z on an 800 x 600 window, a mouse pointer, frames 16 ms apart. */
    static final class Rig {
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        final SpatialIndex index = new SpatialIndex(scene);
        final SceneInteraction interaction = new SceneInteraction();
        final InteractionWorld world = interaction.world(scene, index);
        final Camera camera = new Camera().position(0, 0, 0).set(0, 0, -1, 0, 1, 0, Math.PI / 3, 800, 600, 0.1);
        final InteractionView view = world.view(camera).rect(0, 0, 800, 600);
        final List<String> log = new ArrayList<>();
        long nanos = 1_000_000_000L;
        float x = 400;
        float y = 300;
        int buttons;
        int modifiers;
        float scroll;
        boolean mouse = true;
        boolean blocked;
        PointerKind kind = PointerKind.MOUSE;

        Rig() {
            interaction.pointers(p -> {
                if (mouse) {
                    p.pointer(1, kind).moveTo(x, y).buttons(buttons).modifiers(modifiers).scroll(0, scroll)
                        .blocked(blocked);
                }
            });
        }

        /** A pickable box of half size {@code half} at {@code x, y, z} under {@code parent}. */
        long box(long parent, double bx, double by, double bz, double half) {
            long node = scene.create(parent);
            scene.setTranslation(node, bx, by, bz);
            scene.setLocalBounds(node, -half, -half, -half, half, half, half);
            scene.components().set(node, Pickable.TYPE, Pickable.bounds());
            return node;
        }

        /** Logs every event at {@code node} as "name:TYPE:phase". */
        Rig listen(long node, String name) {
            world.on(node, SceneEventType.ALL_MASK, e -> log.add(name + ":" + e.type() + ":" + e.phase()));
            return this;
        }

        Rig frame() {
            nanos += 16_000_000L;
            interaction.update(nanos);
            scroll = 0;
            return this;
        }

        Rig at(float px, float py) {
            x = px;
            y = py;
            return frame();
        }

        Rig press() {
            buttons |= ScenePointer.PRIMARY;
            return frame();
        }

        Rig release() {
            buttons &= ~ScenePointer.PRIMARY;
            return frame();
        }

        Rig click() {
            return press().release();
        }

        List<String> take() {
            List<String> taken = List.copyOf(log);
            log.clear();
            return taken;
        }
    }

    @Test
    void hoverEntersAndExitsTheHitNodeAndItsAncestorsAndMarksTheObject() {
        Rig r = new Rig();
        long car = r.scene.create();
        long body = r.box(car, 0, 0, -10, 1);
        long other = r.box(NodeHandle.NONE, 3, 0, -10, 1);
        r.listen(car, "car").listen(body, "body").listen(other, "other");
        r.frame();
        assertThat(r.take()).containsExactly("car:ENTER:TARGET", "body:ENTER:TARGET", "body:MOVE:TARGET",
            "car:MOVE:BUBBLE");
        assertThat(r.world.hovered(car)).isTrue();
        assertThat(r.interaction.hovered(body)).isTrue();
        assertThat(r.scene.components().has(body, Hovered.TYPE)).as("its object: the car is not pickable").isTrue();
        assertThat(r.interaction.pointer(0).hovered()).isEqualTo(body);
        assertThat(r.interaction.pointer(0).hitNode()).isEqualTo(body);
        assertThat(r.interaction.pointer(0).hitDistance()).isCloseTo(9, org.assertj.core.data.Offset.offset(1e-9));
        double[] point = new double[3];
        assertThat(r.interaction.pointer(0).hitPoint(point)).isTrue();
        assertThat(point[2]).isCloseTo(-9, org.assertj.core.data.Offset.offset(1e-9));
        r.frame();
        assertThat(r.take()).as("nothing moved").isEmpty();
        float[] right = project(r, 3, 0, -10);
        r.at(right[0], right[1]);
        assertThat(r.take()).containsExactly("body:EXIT:TARGET", "car:EXIT:TARGET", "other:ENTER:TARGET",
            "other:MOVE:TARGET");
        assertThat(r.scene.components().has(body, Hovered.TYPE)).isFalse();
        assertThat(r.scene.components().has(other, Hovered.TYPE)).isTrue();
        r.at(5, 5);
        assertThat(r.take()).containsExactly("other:EXIT:TARGET");
        assertThat(r.world.hovered(other)).isFalse();
        // the pointer leaves
        r.at(right[0], right[1]);
        r.take();
        r.mouse = false;
        r.frame();
        assertThat(r.take()).containsExactly("other:EXIT:TARGET");
        assertThat(r.interaction.pointerCount()).isZero();
        assertThat(r.scene.components().has(other, Hovered.TYPE)).isFalse();
    }

    @Test
    void pressesPassAlongTheRayUntilConsumedAndClicksCount() {
        Rig r = new Rig();
        long front = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        long back = r.box(NodeHandle.NONE, 0, 0, -20, 4);
        boolean[] frontTakes = {false};
        r.world.on(front, SceneEventType.PRESS, e -> {
            r.log.add("front press");
            if (frontTakes[0]) {
                e.consume();
            }
        });
        r.world.on(back, SceneEventType.PRESS, e -> r.log.add("back press"));
        r.world.on(back, SceneEventType.CLICK, e -> r.log.add("back click " + e.clickCount()));
        r.world.on(front, SceneEventType.CLICK, e -> r.log.add("front click " + e.clickCount()));
        r.frame().click();
        assertThat(r.take()).as("front passed it on; the nearest is pressed").containsExactly("front press",
            "back press", "front click 1");
        r.click();
        assertThat(r.take()).containsExactly("front press", "back press", "front click 2");
        frontTakes[0] = true;
        r.nanos += 1_000_000_000L; // too late for a third
        r.click();
        assertThat(r.take()).containsExactly("front press", "front click 1");
        r.press();
        assertThat(r.world.pressed(front)).isTrue();
        assertThat(r.interaction.pointer(0).pressed()).isEqualTo(front);
        r.release();
        assertThat(r.world.pressed(front)).isFalse();
    }

    @Test
    void routesCaptureTargetAndBubbleAndConsumeStops() {
        Rig r = new Rig();
        long root = r.scene.create();
        long mid = r.scene.create(root);
        long leaf = r.box(mid, 0, 0, -10, 1);
        int click = SceneEventType.CLICK.bit();
        r.world.onCapture(root, click, e -> r.log.add("root capture " + e.phase()));
        r.world.on(root, click, e -> r.log.add("root bubble " + e.phase() + " " + (e.target() == leaf)));
        r.world.onCapture(mid, click, e -> r.log.add("mid capture"));
        r.world.on(mid, click, e -> r.log.add("mid bubble"));
        SceneListener leafListener = e -> r.log.add("leaf " + e.phase() + " " + (e.currentNode() == leaf));
        r.world.on(leaf, SceneEventType.CLICK, leafListener);
        r.frame().click();
        assertThat(r.take()).containsExactly("root capture CAPTURE", "mid capture", "leaf TARGET true", "mid bubble",
            "root bubble BUBBLE true");
        SceneListener stop = SceneEvent::consume;
        r.world.onCapture(mid, click, stop);
        r.click();
        assertThat(r.take()).containsExactly("root capture CAPTURE", "mid capture");
        assertThat(r.world.off(mid, stop)).isTrue();
        assertThat(r.world.off(mid, stop)).isFalse();
        assertThat(r.world.off(leaf, leafListener)).isTrue();
        assertThat(r.world.listened(leaf)).isFalse();
        r.click();
        assertThat(r.take()).containsExactly("root capture CAPTURE", "mid capture", "mid bubble",
            "root bubble BUBBLE true");
        // listeners go with their nodes
        r.scene.remove(root);
        assertThat(r.world.listened(mid)).isFalse();
        r.click();
        assertThat(r.take()).isEmpty();
    }

    @Test
    void aPressCapturesThePointerUntilReleasedAndAClickNeedsBoth() {
        Rig r = new Rig();
        long a = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        long b = r.box(NodeHandle.NONE, 3, 0, -10, 1);
        r.world.on(a, SceneEventType.MOVE.bit() | SceneEventType.RELEASE.bit() | SceneEventType.CLICK.bit()
            | SceneEventType.DRAG_START.bit() | SceneEventType.DRAG.bit() | SceneEventType.DRAG_END.bit(),
            e -> r.log.add("a " + e.type() + (e.type() == SceneEventType.DRAG_END ? " " + e.dropped() : "")));
        r.world.on(b, SceneEventType.ALL_MASK, e -> r.log.add("b " + e.type()));
        r.frame().press();
        assertThat(r.interaction.pointer(0).captured()).isEqualTo(a);
        r.take();
        float[] onB = project(r, 3, 0, -10);
        r.at(onB[0], onB[1]);
        assertThat(r.take()).containsExactly("b ENTER", "a DRAG_START", "a DRAG");
        assertThat(r.interaction.pointer(0).dragging()).isTrue();
        r.release();
        assertThat(r.take()).as("the release goes to the capture; no click").containsExactly("a RELEASE",
            "a DRAG_END false");
        assertThat(r.interaction.pointer(0).captured()).isEqualTo(NodeHandle.NONE);
        // pressed on a, released on b without dragging far: no click on either
        r.at(400, 300).take();
        r.press();
        r.at(410, 300);
        r.release();
        assertThat(r.take()).containsExactly("a DRAG_START", "a DRAG", "a RELEASE", "a DRAG_END false");
    }

    @Test
    void dragsCarryPayloadsToNodesThatAcceptThem() {
        Rig r = new Rig();
        long crate = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        long truck = r.box(NodeHandle.NONE, 3, 0, -10, 1);
        long rock = r.box(NodeHandle.NONE, -3, 0, -10, 1);
        r.world.on(crate, SceneEventType.DRAG_START, e -> e.startDrag("crate 7"));
        r.world.on(crate, SceneEventType.DRAG_END, e -> r.log.add("end " + e.dropped()));
        r.world.on(truck, SceneEventType.DROP_OVER.bit() | SceneEventType.DROP_ENTER.bit()
            | SceneEventType.DROP_LEAVE.bit() | SceneEventType.DROP.bit(), e -> {
                r.log.add("truck " + e.type() + " " + e.dragPayload());
                if (e.type() == SceneEventType.DROP_OVER) {
                    e.acceptDrop();
                }
                assertThat(e.dragSource()).isEqualTo(crate);
            });
        r.world.on(rock, SceneEventType.DROP_OVER, e -> r.log.add("rock over"));
        r.world.on(rock, SceneEventType.CLICK, e -> assertThatIllegalStateException().isThrownBy(
            () -> e.startDrag("no")));
        r.frame().press();
        float[] onTruck = project(r, 3, 0, -10);
        r.at(onTruck[0], onTruck[1]);
        assertThat(r.take()).containsExactly("truck DROP_OVER crate 7", "truck DROP_ENTER crate 7");
        float[] onRock = project(r, -3, 0, -10);
        r.at(onRock[0], onRock[1]);
        assertThat(r.take()).containsExactly("rock over", "truck DROP_LEAVE crate 7");
        r.at(onTruck[0], onTruck[1]).take();
        r.release();
        assertThat(r.take()).containsExactly("truck DROP crate 7", "end true");
        r.at(onRock[0], onRock[1]).click(); // the rock's click tries a drag where none starts
    }

    @Test
    void longPressesSuppressClicksAndALongTouchAsksForAContextMenu() {
        Rig r = new Rig();
        long node = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        boolean[] take = {true};
        r.world.on(node, SceneEventType.LONG_PRESS, e -> {
            r.log.add("long");
            if (take[0]) {
                e.consume();
            }
        });
        r.world.on(node, SceneEventType.CLICK.bit() | SceneEventType.CONTEXT.bit(), e -> r.log.add(e.type().name()));
        r.frame().press();
        r.nanos += 600_000_000L;
        r.frame().release();
        assertThat(r.take()).containsExactly("long");
        take[0] = false;
        r.press();
        r.nanos += 600_000_000L;
        r.frame().release();
        assertThat(r.take()).as("unconsumed with a mouse: the click stays").containsExactly("long", "CLICK");
        r.kind = PointerKind.TOUCH;
        r.mouse = false;
        r.frame();
        r.mouse = true;
        r.frame().press();
        r.nanos += 600_000_000L;
        r.frame().release();
        assertThat(r.take()).containsExactly("long", "CONTEXT");
    }

    @Test
    void theSecondaryButtonAsksForAContextMenuAndWheelsPassThrough() {
        Rig r = new Rig();
        long front = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        long back = r.box(NodeHandle.NONE, 0, 0, -20, 4);
        r.world.on(front, SceneEventType.CONTEXT.bit() | SceneEventType.WHEEL.bit() | SceneEventType.PRESS.bit(),
            e -> r.log.add("front " + e.type() + " " + e.button()));
        r.world.on(back, SceneEventType.WHEEL, e -> {
            r.log.add("back wheel " + e.scrollY());
            e.consume();
        });
        r.frame();
        r.buttons = ScenePointer.SECONDARY;
        r.frame();
        r.buttons = 0;
        r.frame();
        r.scroll = 2;
        r.frame();
        assertThat(r.take()).containsExactly("front PRESS 2", "front CONTEXT 2", "front WHEEL 0", "back wheel 2.0");
    }

    @Test
    void aPrimaryPressFocusesItsNode() {
        Rig r = new Rig();
        long a = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        long b = r.box(NodeHandle.NONE, 3, 0, -10, 1);
        r.world.on(a, SceneEventType.FOCUS_IN.bit() | SceneEventType.FOCUS_OUT.bit(), e -> r.log.add("a " + e.type()));
        r.world.on(b, SceneEventType.FOCUS_IN.bit() | SceneEventType.FOCUS_OUT.bit(), e -> r.log.add("b " + e.type()));
        r.frame().click();
        assertThat(r.interaction.focused()).isEqualTo(a);
        float[] onB = project(r, 3, 0, -10);
        r.at(onB[0], onB[1]).click();
        r.at(5, 5).click();
        assertThat(r.take()).containsExactly("a FOCUS_IN", "a FOCUS_OUT", "b FOCUS_IN", "b FOCUS_OUT");
        assertThat(r.interaction.focused()).isEqualTo(NodeHandle.NONE);
    }

    @Test
    void blockedPointersAndPointersOffEveryViewPickNothing() {
        Rig r = new Rig();
        long node = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        r.listen(node, "n");
        r.frame();
        r.take();
        r.blocked = true;
        r.frame();
        assertThat(r.take()).containsExactly("n:EXIT:TARGET");
        assertThat(r.interaction.pointer(0).isBlocked()).isTrue();
        assertThat(r.interaction.pointer(0).picked()).isFalse();
        assertThat(r.interaction.pointer(0).ray(new double[6])).isFalse();
        r.blocked = false;
        r.frame();
        assertThat(r.interaction.pointer(0).view()).isSameAs(r.view);
        assertThat(r.interaction.pointer(0).world()).isSameAs(r.world);
        r.interaction.removeView(r.view);
        r.take();
        r.frame();
        assertThat(r.take()).containsExactly("n:EXIT:TARGET");
        assertThat(r.interaction.viewAt(400, 300)).isNull();
    }

    @Test
    void xrRaysPickInTheirWorld() {
        Rig r = new Rig();
        long node = r.box(NodeHandle.NONE, 5, 0, 0, 1);
        r.listen(node, "n");
        r.mouse = false;
        r.interaction.pointers(p -> p.pointer(3, PointerKind.XR_RAY).ray(0, 0, 0, 1, 0, 0, null)
            .buttons(r.buttons));
        r.frame().click();
        assertThat(r.take()).contains("n:ENTER:TARGET", "n:PRESS:TARGET", "n:CLICK:TARGET");
        assertThat(r.interaction.pointerById(3).kind()).isEqualTo(PointerKind.XR_RAY);
        assertThat(r.interaction.pointerById(3).world()).isSameAs(r.world);
        assertThat(r.interaction.pointerById(42)).isNull();
    }

    @Test
    void cameraNodesAreViewsToo() {
        Rig r = new Rig();
        long node = r.box(NodeHandle.NONE, 0, 10, 0, 1);
        long eye = r.scene.create();
        r.scene.setRotation(eye, Math.sin(Math.PI / 4), 0, 0, Math.cos(Math.PI / 4)); // looking up +Y
        r.scene.components().set(eye, CameraLens.TYPE, CameraLens.perspective(1, 0.1));
        r.interaction.removeView(r.view);
        InteractionView up = r.world.view(eye).rect(0, 0, 800, 600).reach(50);
        assertThat(up.cameraNode()).isEqualTo(eye);
        assertThat(up.camera()).isNull();
        assertThat(up.world()).isSameAs(r.world);
        r.listen(node, "n");
        r.frame();
        assertThat(r.take()).contains("n:ENTER:TARGET");
        // an orthographic lens, a node without a lens
        r.scene.components().get(eye, CameraLens.TYPE).orthographicLens(2, 100);
        r.at(400, 300);
        assertThat(r.interaction.pointer(0).hitNode()).isEqualTo(node);
        r.scene.components().remove(eye, CameraLens.TYPE);
        r.at(401, 300);
        assertThat(r.interaction.pointer(0).picked()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> r.world.view(NodeHandle.of(999, 1)));
    }

    @Test
    void eventsAndPointersAnswerWhatTheyKnow() {
        Rig r = new Rig();
        long node = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        List<String> seen = new ArrayList<>();
        double[] point = new double[3];
        double[] ray = new double[6];
        r.world.on(node, SceneEventType.PRESS, e -> {
            seen.add(e.type() + " " + e.phase() + " " + e.pointerId() + " " + e.pointerKind() + " " + e.player()
                + " " + e.x() + " " + e.y() + " " + e.button() + " " + e.buttons() + " " + e.modifiers() + " "
                + e.hit() + " " + e.hitPoint(point) + " " + e.part() + " " + e.ray(ray) + " " + e.scrollX() + " "
                + e.scrollY() + " " + e.pressX() + " " + e.consumed() + " " + (e.world() == r.world) + " "
                + (e.interaction() == r.interaction) + " " + (e.time() > 0) + " " + e.dragPayload() + " "
                + (e.dragSource() == NodeHandle.NONE) + " " + e.dropped() + " " + e.clickCount());
            assertThat(e.hitDistance()).isCloseTo(9, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(e.toString()).startsWith("PRESS TARGET at node");
            e.capturePointer();
            e.releasePointer();
        });
        r.world.on(node, SceneEventType.RELEASE, e -> {
            seen.add("release hit " + e.hit() + " " + e.hitPoint(point) + " " + e.hitDistance() + " " + e.part());
        });
        r.modifiers = ScenePointer.ALT;
        r.frame().press();
        r.at(5, 5).release();
        assertThat(seen).containsExactly("PRESS TARGET 1 MOUSE 0 400.0 300.0 1 1 4 true true -1 true 0.0 0.0 400.0 "
            + "false true true true null true false 0", "release hit false false Infinity -1");
        ScenePointer p = r.interaction.pointer(0);
        assertThat(p.hitNode()).isEqualTo(NodeHandle.NONE);
        assertThat(p.hitDistance()).isInfinite();
        assertThat(p.hitPoint(point)).isFalse();
        assertThat(p.hitPart()).isEqualTo(-1);
        assertThat(p.player()).isZero();
        assertThat(p.buttonBits()).isZero();
        assertThat(p.modifierBits()).isEqualTo(ScenePointer.ALT);
        assertThat(p.scrollX()).isZero();
        assertThat(p.scrollY()).isZero();
        assertThat(p.x()).isEqualTo(5);
        assertThat(p.y()).isEqualTo(5);
        assertThat(p.captured()).isEqualTo(NodeHandle.NONE);
        assertThat(p.dragging()).isFalse();
        assertThat(p.hits().count()).isZero();
        assertThat(SceneEventType.of(0)).isEqualTo(SceneEventType.ENTER);
        assertThat(SceneEventType.EXIT.bubbles()).isFalse();
        // an orthographic camera, a view off its port, a view of no size
        r.camera.setOrthographic(0, 0, 0, 0, 0, -1, 0, 1, 0, 4, 3, 100, 800, 600);
        r.at(400, 300);
        assertThat(p.hitNode()).isEqualTo(node);
        assertThat(r.view.contains(-1, 0)).isFalse();
        assertThat(r.view.contains(0, -1)).isFalse();
        assertThat(r.view.contains(800, 0)).isFalse();
        assertThat(r.view.contains(0, 600)).isFalse();
        assertThat(r.world.view(r.camera).rect(0, 0, 0, 0).contains(0, 0)).isFalse();
        assertThat(r.interaction.selection().enabled()).isFalse();
        assertThat(r.interaction.selection().primary()).isEqualTo(NodeHandle.NONE);
        assertThat(r.world.updatesScene(false).updatesScene()).isFalse();
        assertThat(r.world.pickLayers(2).pickLayers()).isEqualTo(2);
        assertThat(r.world.interaction()).isSameAs(r.interaction);
        assertThat(r.world.index()).isSameAs(r.index);
        assertThat(r.interaction.rayWorld(r.world)).isSameAs(r.interaction);
    }

    @Test
    void framesAllocateNothing() {
        Rig r = new Rig();
        long node = r.box(NodeHandle.NONE, 0, 0, -10, 1);
        r.box(NodeHandle.NONE, 3, 0, -10, 1);
        r.world.on(node, SceneEventType.ALL_MASK, e -> { });
        r.frame();
        float[] side = project(r, 3, 0, -10);
        int[] i = {0};
        assertAllocationFree(() -> {
            int k = i[0]++ % 6;
            r.x = k < 3 ? 400 : side[0];
            r.buttons = k % 3 == 1 ? ScenePointer.PRIMARY : 0;
            r.frame();
        });
    }

    /** Window pixels of world point {@code x, y, z} as the rig's camera sees it. */
    static float[] project(Rig r, double x, double y, double z) {
        double f = 1 / Math.tan(Math.PI / 6);
        double aspect = 800.0 / 600.0;
        double nx = x / -z * f / aspect;
        double ny = y / -z * f;
        return new float[] {(float) ((nx + 1) / 2 * 800), (float) ((1 - ny) / 2 * 600)};
    }
}
