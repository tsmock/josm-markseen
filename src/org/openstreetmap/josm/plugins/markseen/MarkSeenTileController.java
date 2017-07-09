package org.openstreetmap.josm.plugins.markseen;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;

import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.TileController;
import org.openstreetmap.gui.jmapviewer.interfaces.TileCache;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;
import org.openstreetmap.gui.jmapviewer.interfaces.TileSource;

import org.openstreetmap.josm.data.Bounds;


public class MarkSeenTileController extends TileController {
    // is there a better way to create a properly read-only BufferedImage? don't know. for now, all we want to be able
    // to do is catch potential bugs where something attempts to write to a mask that is intended to be shared &
    // constant
    static class WriteInhibitedBufferedImage extends BufferedImage {
        public boolean inhibitWrites = false;

        public WriteInhibitedBufferedImage(int width, int height, int imageType, IndexColorModel cm, Color constColor) {
            super(width, height, imageType, cm);
            Graphics2D g = this.createGraphics();
            g.setBackground(constColor);
            g.clearRect(0, 0, width, height);
            this.inhibitWrites = true;
        }

        @Override
        public WritableRaster getWritableTile(int tileX, int tileY) {
            if (this.inhibitWrites) {
                throw new RuntimeException("Attempt to draw to WriteInhibitedBufferedImage with inhibitWrites set");
            } else {
                return super.getWritableTile(tileX, tileY);
            }
        }
    }

    public final WriteInhibitedBufferedImage EMPTY_MASK;
    public final WriteInhibitedBufferedImage FULL_MASK;

    private static IndexColorModel maskColorModel;
    public static IndexColorModel getMaskColorModel() {
        return maskColorModel;
    }

    static {
        maskColorModel = new IndexColorModel(
            1,
            2,
            new byte[]{(byte)0, (byte)255},
            new byte[]{(byte)0, (byte)0},
            new byte[]{(byte)0, (byte)255},
            new byte[]{(byte)0, (byte)128}
        );
    }

    public final ReentrantReadWriteLock quadTreeRWLock = new ReentrantReadWriteLock();

    private QuadTreeNode quadTreeRoot;
    public QuadTreeNode getQuadTreeRoot() {
        return this.quadTreeRoot;
    }

    public MarkSeenTileController(TileSource source, TileCache tileCache, TileLoaderListener listener) {
        super(source, tileCache, listener);

        this.EMPTY_MASK = new WriteInhibitedBufferedImage(
            source.getTileSize(),
            source.getTileSize(),
            BufferedImage.TYPE_BYTE_BINARY,
            this.maskColorModel,
            new Color(0,0,0,0)
        );
        this.FULL_MASK = new WriteInhibitedBufferedImage(
            source.getTileSize(),
            source.getTileSize(),
            BufferedImage.TYPE_BYTE_BINARY,
            this.maskColorModel,
            new Color(255,255,255,255)
        );
        this.quadTreeRoot = new QuadTreeNode(this);
        this.quadTreeRWLock.writeLock().lock();
        this.quadTreeRoot.markRectSeen(new Bounds(51.36, -0.35, 51.61, 0.10), 4, this);
        this.quadTreeRWLock.writeLock().unlock();
    }

    /**
     * Verbatim copy of TileController.getTile, generating MarkSeenTiles instead of regular Tiles
     */
    @Override
    public Tile getTile(int tilex, int tiley, int zoom) {
        int max = 1 << zoom;
        if (tilex < 0 || tilex >= max || tiley < 0 || tiley >= max)
            return null;
        Tile tile = tileCache.getTile(tileSource, tilex, tiley, zoom);
        if (tile == null) {
            tile = new MarkSeenTile(this, tileSource, tilex, tiley, zoom);
            tileCache.addTile(tile);
            tile.loadPlaceholderFromCache(tileCache);
        }
        if (tile.hasError()) {
            tile.loadPlaceholderFromCache(tileCache);
        }
        if (!tile.isLoaded()) {
            tileLoader.createTileLoaderJob(tile).submit();
        }
        return tile;
    }
}
