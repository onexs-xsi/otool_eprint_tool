package com.otool.eprint.scan.opencv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 只覆盖不依赖 OpenCV native 的纯几何静态方法（角点排序、四边形校验），
 * JVM 单测环境不加载 OpenCV 库。
 */
public final class OpenCVLabelRectifierTest {
    @Test
    public void sortsShuffledCornersToTopLeftTopRightBottomRightBottomLeft() {
        // 故意打乱顺序的轻微梯形四角。
        float[] corners = {108, 90, 10, 10, 8, 88, 110, 12};
        float[] ordered = OpenCVLabelRectifier.sortQuadCorners(corners);
        assertEquals(10f, ordered[0], 0.001f);
        assertEquals(10f, ordered[1], 0.001f);
        assertEquals(110f, ordered[2], 0.001f);
        assertEquals(12f, ordered[3], 0.001f);
        assertEquals(108f, ordered[4], 0.001f);
        assertEquals(90f, ordered[5], 0.001f);
        assertEquals(8f, ordered[6], 0.001f);
        assertEquals(88f, ordered[7], 0.001f);
    }

    @Test
    public void acceptsConvexQuadCoveringMostOfImage() {
        // 640x480 画面里一个居中的透视梯形标签。
        float[] ordered = OpenCVLabelRectifier.sortQuadCorners(
                new float[]{80, 60, 560, 72, 548, 420, 92, 432});
        assertTrue(OpenCVLabelRectifier.isUsableQuad(ordered, 640, 480));
    }

    @Test
    public void validApproxQuadAcceptsShuffledConvexQuad() {
        // 与 acceptsConvexQuadCoveringMostOfImage 相同的梯形，但角点顺序打乱。
        float[] corners = {560, 72, 80, 60, 92, 432, 548, 420};
        assertTrue(OpenCVLabelRectifier.isValidApproxQuad(corners, 640, 480));
    }

    @Test
    public void validApproxQuadRejectsOutOfBoundsTinyAndMalformedQuads() {
        // 角点明显越界。
        assertFalse(OpenCVLabelRectifier.isValidApproxQuad(
                new float[]{-400, -400, 1000, 60, 900, 900, 60, 420}, 640, 480));
        // 面积占比过小。
        assertFalse(OpenCVLabelRectifier.isValidApproxQuad(
                new float[]{100, 100, 130, 100, 130, 130, 100, 130}, 640, 480));
        // 入参非法。
        assertFalse(OpenCVLabelRectifier.isValidApproxQuad(null, 640, 480));
        assertFalse(OpenCVLabelRectifier.isValidApproxQuad(new float[6], 640, 480));
    }

    @Test
    public void rejectsDegenerateSelfIntersectingAndOutOfBoundsQuads() {
        // 面积过小的四边形。
        float[] tiny = OpenCVLabelRectifier.sortQuadCorners(
                new float[]{100, 100, 130, 100, 130, 130, 100, 130});
        assertFalse(OpenCVLabelRectifier.isUsableQuad(tiny, 640, 480));
        // 自交（蝴蝶结）四边形：交叉积符号不一致。
        float[] selfCrossing = {100, 100, 300, 300, 100, 300, 300, 100};
        assertFalse(OpenCVLabelRectifier.isUsableQuad(selfCrossing, 640, 480));
        // 角点明显越界的四边形。
        float[] outOfBounds = OpenCVLabelRectifier.sortQuadCorners(
                new float[]{-400, -400, 1000, 60, 900, 900, 60, 420});
        assertFalse(OpenCVLabelRectifier.isUsableQuad(outOfBounds, 640, 480));
        // 入参非法。
        assertFalse(OpenCVLabelRectifier.isUsableQuad(null, 640, 480));
        assertFalse(OpenCVLabelRectifier.isUsableQuad(selfCrossing, 0, 480));
        assertFalse(OpenCVLabelRectifier.isUsableQuad(new float[4], 640, 480));
    }
}
