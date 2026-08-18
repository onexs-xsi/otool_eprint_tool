package com.otool.eprint.scan.ocr;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DbPostProcessorTest {

    /** 生成 width×height 概率图，矩形 [x0,x1]×[y0,y1] 内为 value，其余为 0。 */
    private static float[] mapWithRect(int width, int height,
                                       int x0, int y0, int x1, int y1, float value) {
        float[] prob = new float[width * height];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                prob[y * width + x] = value;
            }
        }
        return prob;
    }

    @Test
    public void findBoxes_detectsBrightRectWithUnclipExpansion() {
        // 64×32 概率图，亮块 [10,50]×[8,16]（w=40, h=8），unclip 距离 = 320*1.5/96 = 5。
        float[] prob = mapWithRect(64, 32, 10, 8, 50, 16, 0.9f);
        List<DbPostProcessor.Box> boxes =
                DbPostProcessor.findBoxes(prob, 64, 32, 0.3f, 0.5f, 1.5f);
        assertEquals(1, boxes.size());
        float[] p = boxes.get(0).points;
        // unclip 外扩后约 [5,55]×[3,21]，允许浮点误差。
        assertEquals(5f, p[0], 0.6f);
        assertEquals(3f, p[1], 0.6f);
        assertEquals(55f, p[4], 0.6f);
        assertEquals(21f, p[5], 0.6f);
        assertTrue(boxes.get(0).score > 0.85f);
    }

    @Test
    public void findBoxes_dropsLowScoreRegion() {
        // 二值化阈值 0.3 以下全部过滤。
        float[] prob = mapWithRect(64, 32, 10, 8, 50, 16, 0.2f);
        assertTrue(DbPostProcessor.findBoxes(prob, 64, 32, 0.3f, 0.5f, 1.5f).isEmpty());
    }

    @Test
    public void findBoxes_dropsRegionBelowBoxThreshold() {
        // 超过二值阈值但低于框均值阈值 0.5。
        float[] prob = mapWithRect(64, 32, 10, 8, 50, 16, 0.4f);
        assertTrue(DbPostProcessor.findBoxes(prob, 64, 32, 0.3f, 0.5f, 1.5f).isEmpty());
    }

    @Test
    public void findBoxes_ordersBoxesTopToBottom() {
        float[] prob = new float[96 * 64];
        // 下方亮块先写入、上方后写入，验证输出按纵向排序。
        for (int y = 40; y <= 48; y++) for (int x = 10; x <= 50; x++) prob[y * 96 + x] = 0.9f;
        for (int y = 8; y <= 16; y++) for (int x = 10; x <= 50; x++) prob[y * 96 + x] = 0.9f;
        List<DbPostProcessor.Box> boxes =
                DbPostProcessor.findBoxes(prob, 96, 64, 0.3f, 0.5f, 1.5f);
        assertEquals(2, boxes.size());
        assertTrue(boxes.get(0).points[1] < boxes.get(1).points[1]);
    }

    @Test
    public void minAreaRect_recoversRotatedRect() {
        // 旋转 30° 的 40×10 矩形，验证凸包 + 旋转卡壳恢复尺寸。
        int width = 128;
        java.util.List<Integer> pixels = new java.util.ArrayList<>();
        double cos = Math.cos(Math.toRadians(30));
        double sin = Math.sin(Math.toRadians(30));
        for (int v = 0; v <= 10; v++) {
            for (int u = 0; u <= 40; u++) {
                int x = (int) Math.round(64 + u * cos - v * sin);
                int y = (int) Math.round(32 + u * sin + v * cos);
                pixels.add(y * width + x);
            }
        }
        int[] component = pixels.stream().mapToInt(Integer::intValue).toArray();
        float[] rect = DbPostProcessor.minAreaRect(component, width);
        assertNotNull(rect);
        DbPostProcessor.Box box = new DbPostProcessor.Box(rect, 1f);
        float w1 = DbPostProcessor.Box.dist(rect[0], rect[1], rect[2], rect[3]);
        float h1 = DbPostProcessor.Box.dist(rect[2], rect[3], rect[4], rect[5]);
        assertEquals(40f, Math.max(w1, h1), 1.5f);
        assertEquals(10f, Math.min(w1, h1), 1.5f);
        assertTrue(box.shortSide() > 0f);
    }

    @Test
    public void unclip_expandsAxisAlignedRect() {
        // 100×20 矩形，distance = 2000*1.5/240 = 12.5 → 125×45。
        float[] rect = {0, 0, 100, 0, 100, 20, 0, 20};
        float[] out = DbPostProcessor.unclip(rect, 1.5f);
        assertEquals(-12.5f, out[0], 0.6f);
        assertEquals(-12.5f, out[1], 0.6f);
        assertEquals(112.5f, out[4], 0.6f);
        assertEquals(32.5f, out[5], 0.6f);
    }

    @Test
    public void scaleBox_mapsToSourceCoordinates() {
        float[] box = {1, 2, 3, 4, 5, 6, 7, 8};
        float[] scaled = DbPostProcessor.scaleBox(box, 2f, 0.5f);
        assertEquals(2f, scaled[0], 1e-6f);
        assertEquals(1f, scaled[1], 1e-6f);
        assertEquals(14f, scaled[6], 1e-6f);
        assertEquals(4f, scaled[7], 1e-6f);
    }

    @Test
    public void orderCorners_assignsTlTrBrBl() {
        float[] corners = {10, 10, 0, 10, 0, 0, 10, 0}; // bl, tl? 乱序输入
        float[] out = DbPostProcessor.orderCorners(corners);
        assertEquals(0f, out[0], 1e-6f);   // tl (0,0)
        assertEquals(0f, out[1], 1e-6f);
        assertEquals(10f, out[2], 1e-6f);  // tr (10,0)
        assertEquals(0f, out[3], 1e-6f);
        assertEquals(10f, out[4], 1e-6f);  // br (10,10)
        assertEquals(10f, out[5], 1e-6f);
        assertEquals(0f, out[6], 1e-6f);   // bl (0,10)
        assertEquals(10f, out[7], 1e-6f);
    }
}
