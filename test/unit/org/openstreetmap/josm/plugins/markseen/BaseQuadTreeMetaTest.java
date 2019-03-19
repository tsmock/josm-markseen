package org.openstreetmap.josm.plugins.markseen;

import java.io.IOException;
import java.lang.AssertionError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import java.awt.image.DataBufferByte;

import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.interfaces.TileSource;
import org.openstreetmap.josm.data.Bounds;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Ignore;
import mockit.Mock;
import mockit.MockUp;
import mockit.Deencapsulation;
import mockit.Invocation;


@Ignore
public class BaseQuadTreeMetaTest extends BaseRectTest {
    public static QuadTreeNodeDynamicReference[] createDynamicReferences(QuadTreeMeta quadTreeMeta, Object[][] referenceTiles_) {
        new MockUp<Tile>() {
            @Mock void $init(Invocation invocation, TileSource source, int xtile, int ytile, int zoom) {
                Tile tile = invocation.getInvokedInstance();
                Deencapsulation.setField(tile, "xtile", xtile);
                Deencapsulation.setField(tile, "ytile", ytile);
                Deencapsulation.setField(tile, "zoom", zoom);
            }
        };
        QuadTreeNodeDynamicReference[] refs = new QuadTreeNodeDynamicReference[referenceTiles_.length];
        for (int i=0; i<referenceTiles_.length; i++) {
            Tile mockTile = new Tile(null, (int)referenceTiles_[i][1], (int)referenceTiles_[i][2], (int)referenceTiles_[i][0]);
            refs[i] = new QuadTreeNodeDynamicReference(quadTreeMeta, mockTile);
        }
        return refs;
    }

    public BaseQuadTreeMetaTest(int scenarioIndex_, Integer seenRectOrderSeed_, Integer referenceTileOrderSeed_)
    throws IOException {
        super(scenarioIndex_, seenRectOrderSeed_, referenceTileOrderSeed_);
    }

    protected void markRectsAsync(QuadTreeMeta quadTreeMeta, Object[][] seenRects_, Integer orderSeed) {
        List<Integer> remapping = getRemapping(seenRects_.length, orderSeed);

        for (int i = 0; i<seenRects_.length; i++) {
            int j = remapping.get(i);
            Object[] seenRectInfo = seenRects_[j];
            System.out.format("(%d of %d) Requesting seen rect mark %d\n", i, seenRects_.length, j);
            Bounds bounds = (Bounds)seenRectInfo[0];
            double minTilesAcross = (double)seenRectInfo[1];

            boolean success = false;
            while (!success) {
                try {
                    quadTreeMeta.requestSeenBoundsMark(bounds, minTilesAcross, true);
                    success = true;
                } catch (RejectedExecutionException e) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e2) {}
                    // then retry
                }
            }
        }
    }

    protected void inspectReferenceTiles(QuadTreeMeta quadTreeMeta, QuadTreeNodeDynamicReference[] dynamicReferences, Object [][] referenceTiles_, Integer orderSeed) {
        this.inspectReferenceTiles(quadTreeMeta, dynamicReferences, referenceTiles_, orderSeed, true);
    }

    protected void inspectReferenceTiles(QuadTreeMeta quadTreeMeta, QuadTreeNodeDynamicReference[] dynamicReferences, Object [][] referenceTiles_, Integer orderSeed, boolean assertContents) {
        this.inspectReferenceTiles(quadTreeMeta, dynamicReferences, referenceTiles_, orderSeed, assertContents, null);
    }

    protected void inspectReferenceTiles(
        QuadTreeMeta quadTreeMeta,
        QuadTreeNodeDynamicReference[] dynamicReferences,
        Object [][] referenceTiles_,
        Integer orderSeed,
        boolean assertContents,
        Object constReferenceMask
    ) {
        assert dynamicReferences.length == referenceTiles_.length;

        List<Integer> remapping = getRemapping(referenceTiles_.length, orderSeed);

        for (int i = 0; i<referenceTiles_.length; i++) {
            final int j = remapping.get(i);
            Object[] referenceTileInfo = referenceTiles_[j];
            System.out.format("(%d of %d) Checking reference tile %d\n", i, referenceTiles_.length, j);
            byte[] refMaskBytes = getRefMaskBytes(quadTreeMeta, constReferenceMask != null ? constReferenceMask : referenceTileInfo[3]);

            byte[] resultMaskBytes = dynamicReferences[j].maskReadOperation(
                mask -> ((DataBufferByte) mask.getData().getDataBuffer()).getData()
            );

            if (assertContents) {
                try {
                    assertArrayEquals(
                        resultMaskBytes,
                        refMaskBytes
                    );
                } catch (final AssertionError e) {
                    System.out.format("assertArrayEquals failed on reference tile %d\n", j);
                    System.out.println(javax.xml.bind.DatatypeConverter.printHexBinary(resultMaskBytes));
                    throw e;
                }
            }
        }
    }

    protected List<Future<Object>> fetchTileMasksAsync(
        final QuadTreeMeta quadTreeMeta,
        final QuadTreeNodeDynamicReference[] dynamicReferences,
        final ExecutorService executor,
        final Integer orderSeed
    ) {
        List<Integer> remapping = getRemapping(dynamicReferences.length, orderSeed);
        List<Future<Object>> maskFutures = new ArrayList<Future<Object>>(dynamicReferences.length);
        for (int i = 0; i<dynamicReferences.length; i++) {
            maskFutures.add(null);
        }

        for (int i = 0; i<dynamicReferences.length; i++) {
            final int j = remapping.get(i);
            System.out.format("(%d of %d) Requesting tile mask %d\n", i, dynamicReferences.length, j);

            maskFutures.set(j, executor.submit(() -> dynamicReferences[j].maskReadOperation(
                mask -> {
                    // returning actual false & true here (cf referenceTile masks) would allow a caller to test for
                    // these special masks
                    if (mask == quadTreeMeta.EMPTY_MASK) {
                        return false;
                    } else if (mask == quadTreeMeta.FULL_MASK) {
                        return true;
                    } else {
                        return ((DataBufferByte) mask.getData().getDataBuffer()).getData();
                    }
                }
            )));
        }

        return maskFutures;
    }
}
