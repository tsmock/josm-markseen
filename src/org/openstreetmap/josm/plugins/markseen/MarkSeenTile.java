package org.openstreetmap.josm.plugins.markseen;

import java.lang.ref.WeakReference;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import java.util.Random;

import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.interfaces.TileSource;

public class MarkSeenTile extends Tile {
    private WeakReference<QuadTreeNode> quadTreeNodeMemo;
    private MarkSeenTileController tileController;

    public MarkSeenTile(MarkSeenTileController controller, TileSource source, int xtile, int ytile, int zoom) {
        this(controller, source, xtile, ytile, zoom, LOADING_IMAGE);
    }

    public MarkSeenTile(
        MarkSeenTileController controller,
        TileSource source,
        int xtile,
        int ytile,
        int zoom,
        BufferedImage image
    ) {
        super(source, xtile, ytile, zoom, image);
        this.tileController = controller;
    }

    protected QuadTreeNode getQuadTreeNode(boolean write) {
        QuadTreeNode node;
        if (this.quadTreeNodeMemo != null) {
            node = this.quadTreeNodeMemo.get();
            if (node != null) {
                return node;
            }
        }
        node = this.tileController.getQuadTreeRoot().getNodeForTile(
            this.xtile,
            this.ytile,
            this.zoom,
            write,
            this.tileController
        );
        if (node == null) {
            // there's nothing more we can do without write access
            return null;
        }
        this.quadTreeNodeMemo = new WeakReference<QuadTreeNode>(node);
        return node;
    }

    protected void paintInner(Graphics g, int x, int y, int width, int height, boolean ignoreWH) {
        // attempt with read-lock first
        this.tileController.quadTreeRWLock.readLock().lock();
        QuadTreeNode node = this.getQuadTreeNode(false);
        if (node == null) {
            // operation could not be performed with only a read-lock, we'll have to drop the read-lock and
            // reacquire with write lock so that any required resources can be created or modified
            this.tileController.quadTreeRWLock.readLock().unlock();
            this.tileController.quadTreeRWLock.writeLock().lock();
            node = this.getQuadTreeNode(true);
        }

        // if we already have the write-lock we won't drop it - it's likely we'll need the write-lock to perform
        // getMask if this tile didn't previously have a valid quadTreeNodeMemo
        BufferedImage mask_ = node.getMask(
            this.tileController.quadTreeRWLock.isWriteLockedByCurrentThread(),
            this.tileController
        );
        if (mask_ == null) {
            // this should only have been possible if we hadn't already taken the write-lock
            assert !this.tileController.quadTreeRWLock.isWriteLockedByCurrentThread();
            // operation could not be performed with only a read-lock, we'll have to drop the read-lock and
            // reacquire with write lock so that any required resources can be created or modified
            this.tileController.quadTreeRWLock.readLock().unlock();
            this.tileController.quadTreeRWLock.writeLock().lock();
            mask_ = node.getMask(true, this.tileController);
        }

        if (ignoreWH) {
            g.drawImage(mask_, x, y, null);
        } else {
            g.drawImage(mask_, x, y, width, height, null);
        }

        // release whichever lock we had
        if (this.tileController.quadTreeRWLock.isWriteLockedByCurrentThread()) {
            this.tileController.quadTreeRWLock.writeLock().unlock();
        } else {
            this.tileController.quadTreeRWLock.readLock().unlock();
        }
    }

    @Override
    public void paint(Graphics g, int x, int y) {
        super.paint(g, x, y);
        this.paintInner(g, x, y, 0, 0, true);
    }

    @Override
    public void paint(Graphics g, int x, int y, int width, int height) {
        super.paint(g, x, y, width, height);
        this.paintInner(g, x, y, width, height, false);
    }
}
