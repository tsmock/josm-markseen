package org.openstreetmap.josm.plugins.markseen;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;

import java.lang.Math;
import java.lang.ref.SoftReference;
import java.util.Arrays;

import org.openstreetmap.gui.jmapviewer.JMapViewer;
import org.openstreetmap.gui.jmapviewer.OsmMercator;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.Bounds;

class QuadTreeNode {
    private SoftReference<BufferedImage> mask;
    private BufferedImage canonicalMask;

    private QuadTreeNode parent;
    private boolean belowCanonical;
    private boolean dirty;

    /** Children listed in z-order, x-minor */
    private final QuadTreeNode [] children = {null, null, null, null};

    /** Intended for constructing the root node */
    public QuadTreeNode(MarkSeenTileController tileController) {
        this.belowCanonical = false;

        this.canonicalMask = tileController.getEmptyMask();
        this.mask = new SoftReference<BufferedImage>(this.canonicalMask);
    }

    /** Intended for constructing child nodes */
    public QuadTreeNode(QuadTreeNode parent) {
        assert parent != null;
        this.parent = parent;
        this.belowCanonical = true;
    }

    private static BufferedImage newBufferedImage(MarkSeenTileController tileController) {
        int tileSize = tileController.getTileSource().getTileSize();
        assert tileSize > 0;
        return new BufferedImage(
            tileSize,
            tileSize,
            BufferedImage.TYPE_BYTE_BINARY,
            tileController.getMaskColorModel()
        );
    }

    private boolean isAboveCanonical() {
        return this.canonicalMask == null && !this.belowCanonical;
    }

    private QuadTreeNode getChild(int childIndex, boolean write) {
        QuadTreeNode child = this.children[childIndex];
        if (child == null && write) {
            this.children[childIndex] = child = new QuadTreeNode(this);
        }
        return child;
    }

    public QuadTreeNode getNodeForTile(
        int xtile,
        int ytile,
        int zoom,
        boolean write,
        MarkSeenTileController tileController
    ) {
        assert (!write) || tileController.getQuadTreeRWLock().isWriteLockedByCurrentThread();
        // if we don't know we're the root we can't be sure we're not belowCanonical
        assert parent == null;

        return getNodeForTileInner(0, 0, 0, xtile, ytile, zoom, false, write);
    }

    private QuadTreeNode getNodeForTileInner(
        int xtileThis,
        int ytileThis,
        int zoomThis,
        int xtileTarget,
        int ytileTarget,
        int zoomTarget,
        boolean recBelowCanonical,
        boolean write
    ) {
        assert zoomTarget <= JMapViewer.MAX_ZOOM;
        if (zoomThis == zoomTarget) {
            assert xtileThis == xtileTarget;
            assert ytileThis == ytileTarget;
            return this;
        }
        // assert this tile is actually beneath us
        assert zoomThis < zoomTarget;
        assert (xtileThis << (zoomTarget-zoomThis)) <= xtileTarget;
        assert xtileTarget <= ((xtileThis << (zoomTarget-zoomThis)) + (1<<(zoomTarget-zoomThis)));
        assert (ytileThis << (zoomTarget-zoomThis)) <= ytileTarget;
        assert ytileTarget <= ((ytileThis << (zoomTarget-zoomThis)) + (1<<(zoomTarget-zoomThis)));
        // also assert consistency of belowCanonical
        assert this.belowCanonical == recBelowCanonical;

        int childIndex = 0;
        int xtileNext = 2*xtileThis;
        int ytileNext = 2*ytileThis;

        if (xtileTarget >= ((xtileThis << (zoomTarget-zoomThis)) + (1<<((zoomTarget-zoomThis)-1)))) {
            childIndex |= 1;
            xtileNext++;
        }
        if (ytileTarget >= ((ytileThis << (zoomTarget-zoomThis)) + (1<<((zoomTarget-zoomThis)-1)))) {
            childIndex |= (1<<1);
            ytileNext++;
        }

        QuadTreeNode child = this.getChild(childIndex, write);
        if (child == null) {
            // signal that we can't perform our job without write access
            return null;
        }

        if (zoomTarget-zoomThis == 1) {
            return child;
        } else {
            return child.getNodeForTileInner(
                xtileNext,
                ytileNext,
                zoomThis+1,
                xtileTarget,
                ytileTarget,
                zoomTarget,
                this.belowCanonical || (this.canonicalMask != null),
                write
            );
        }
    }

    private BufferedImage transformedToDescendant(
        AffineTransform affineTransform,
        BufferedImage targetImage,  // can be null if descendant doesn't have an allocated image to donate
        QuadTreeNode child,
        MarkSeenTileController tileController
    ) {
        int childIndex = Arrays.asList(this.children).indexOf(child);
        assert childIndex != -1;

        int tileSize = tileController.getTileSource().getTileSize();

        // when traversing *up* the quadtree, adding the transform to the AffineTransform must happen *after*
        // propagating the recursion because it's the *parent* (the callee) which holds the information about the
        // child's positioning.

        // affineTransform was supplied to us as the AffineTransform that the calling child would have used had it
        // drawn *itself* for the descendant. we have to transform it so that has the appropriate transform for
        // *us* to use for drawing.
        if ((childIndex & 1) != 0) {
            affineTransform.translate(-tileSize, 0);
        }
        if ((childIndex & (1<<1)) != 0) {
            affineTransform.translate(0, -tileSize);
        }
        affineTransform.scale(2, 2);

        // using a `false` write arg here because we don't want to bother generating a mask which is only going to be
        // used as an intermediary
        BufferedImage mask_ = this.getMask(false, tileController);
        if (mask_ == tileController.getEmptyMask() || mask_ == tileController.getFullMask()) {
            return mask_;
        } else if (mask_ != null) {
            if (targetImage == null) {
                // we have to allocate an image ourselves
                targetImage = this.newBufferedImage(tileController);
            }
            Graphics2D g = targetImage.createGraphics();
            g.drawImage(mask_, affineTransform, null);
            return targetImage;
        } else {
            // we don't currently have a valid mask to use. recurse.
            return this.parent.transformedToDescendant(affineTransform, targetImage, this, tileController);
        }
    }

    /** Effectively a way of accessing the latter half of drawOntoAncestor, needed for initial entry point into
     *  recursion */
    private void drawChildrenOntoAncestor(Graphics2D g, MarkSeenTileController tileController) {
        assert this.isAboveCanonical();

        int tileSize = tileController.getTileSource().getTileSize();
        Graphics2D gCopy;
        QuadTreeNode child;

        for (int i=0; i<children.length; i++) {
            child = this.children[i];
            // we shouldn't be encountering null children above canonical level
            assert child != null;

            // when traversing *down* the quadtree, the transform of the g object has to be performed *before*
            // propagating the recursion because it's the *parent* (the caller) which holds the information about
            // the child's positioning

            gCopy = (Graphics2D) g.create();
            gCopy.scale(0.5, 0.5);
            if ((i & 1) != 0) {
                gCopy.translate(tileSize, 0);
            }
            if ((i & (1<<1)) != 0) {
                gCopy.translate(0, tileSize);
            }
            child.drawOntoAncestor(gCopy, tileController);
            gCopy.dispose();
        }
    }

    private void drawOntoAncestor(Graphics2D g, MarkSeenTileController tileController) {
        assert !this.belowCanonical;
        // using a `false` write arg here because we don't want to bother generating a mask which is only going to be
        // used as an intermediary
        BufferedImage mask_ = this.getMask(false, tileController);
        if (mask_ != null) {
            g.drawImage(mask_, new AffineTransform(), null);
        } else {
            this.drawChildrenOntoAncestor(g, tileController);
        }
    }

    public BufferedImage getMask(boolean write, MarkSeenTileController tileController) {
        assert (!write) || tileController.getQuadTreeRWLock().isWriteLockedByCurrentThread();

        if (this.canonicalMask != null) {
            return this.canonicalMask;
        }

        BufferedImage mask_;
        if (this.mask == null) {
            if (!write) {
                // there's nothing more we can do without write access
                return null;
            }
            mask_ = null;
        } else {
            mask_ = this.mask.get();
        }

        if (this.dirty || mask_ == null) {
            // we're going to have to redraw our mask from descendents or ancestors
            if (!write) {
                // there's nothing more we can do without write access
                return null;
            }

            if (this.belowCanonical) {
                mask_ = this.parent.transformedToDescendant(
                    new AffineTransform(),
                    mask_ != tileController.getEmptyMask() && mask_ != tileController.getFullMask() ? mask_ : null,
                    this,
                    tileController
                );
                this.mask = new SoftReference<BufferedImage>(mask_);
            } else {
                // TODO make this memory efficient
                if (mask_ == null || mask_ == tileController.getEmptyMask() || mask_ == tileController.getFullMask()) {
                    // drawChildrenOntoAncestor needs a writable image pre-allocated for it
                    mask_ = this.newBufferedImage(tileController);
                    this.mask = new SoftReference<BufferedImage>(mask_);
                }
                int tileSize = tileController.getTileSource().getTileSize();
                Graphics2D g = mask_.createGraphics();
                g.setBackground(new Color(0,0,0,0));
                g.clearRect(0, 0, tileSize, tileSize);
                this.drawChildrenOntoAncestor(g, tileController);
            }
            this.dirty = false;
        }

        return mask_;
    }

    private void dirtyAncestors(boolean dirtySelf) {
        if (dirtySelf) {
            this.dirty = true;
        }
        if (this.parent != null) {
            this.parent.dirtyAncestors(true);
        }
    }

    private void dirtyDescendants(boolean dirtySelf) {
        if (dirtySelf) {
            this.dirty = true;
        }
        for (int i=0; i<this.children.length; i++) {
            QuadTreeNode child = this.getChild(i, false);
            if (child != null) {
                child.dirtyDescendants(true);
            }
        }
    }

    private void setDescendantsBelowCanonical(boolean setSelf) {
        if (setSelf) {
            this.canonicalMask = null;
            this.belowCanonical = true;
        }
        for (int i=0; i<this.children.length; i++) {
            QuadTreeNode child = this.getChild(i, false);
            if (child != null) {
                child.setDescendantsBelowCanonical(true);
            }
        }
    }

    private void markRectSeenInner(
        int xThis,
        int yThis,
        int zoomThis,
        double x0,
        double y0,
        double x1,
        double y1,
        int preferredZoom,
        MarkSeenTileController tileController
    ) {
        int tileSize = tileController.getTileSource().getTileSize();
        // the central task of this method is to descend the quadtree to the point it can classify each branch as
        // either fully contained by bbox, fully outside bbox or is forced to create a new node at or below
        // preferredZoom
        if (x1 < xThis || x0 > xThis+tileSize || y1 < yThis || y0 > yThis+tileSize) {
            Main.debug("Tile "+zoomThis+"/"+(xThis/tileSize)+"/"+(yThis/tileSize)+": ignoring\n");
            // this tile lies completely outside the rect
            if (this.belowCanonical) {
                // we have to claim canonicalism for this node - an ancestor must be being split for us to have arrived
                // here (also we should be able to rely on any ancestors relinquishing their canonicalism during the
                // unwind without us having to do anything about it)
                this.canonicalMask = this.getMask(true, tileController);
                this.belowCanonical = false;
            }
            // otherwise nothing else to do
        } else if (x0 < xThis && x1 > xThis+tileSize && y0 < yThis && y1 > yThis+tileSize) {
            // this tile lies completely inside the rect - make this node canonical, set mask to all-seen (unless this
            // is already the case)
            if (this.canonicalMask != tileController.getFullMask()) {
                Main.debug("Tile "+zoomThis+"/"+(xThis/tileSize)+"/"+(yThis/tileSize)+": marking as FULL_MASK\n");
                this.canonicalMask = tileController.getFullMask();
                this.mask = new SoftReference<BufferedImage>(this.canonicalMask);

                this.setDescendantsBelowCanonical(false);
                this.belowCanonical = false;

                // mark ancestors & descendants dirty
                this.dirtyAncestors(false);
                this.dirtyDescendants(false);
            }
        // TODO optimization
        // } else if (this node's mask is FULL_MASK anyway) {
        //     claim canonicalism (potentially from above?)
        //     do nothing else
        // }
        } else {
            // tile straddles at least one edge of rect
            if (zoomThis < preferredZoom || this.isAboveCanonical()) {
                // we're at too low a zoom level to start any drawing - we should recurse, which will also have the
                // effect of more finely pinning down the edge
                for (int i=0; i<this.children.length; i++) {
                    QuadTreeNode child = this.getChild(i, true);
                    child.markRectSeenInner(
                        (xThis*2)+((i&1) != 0 ? tileSize : 0),
                        (yThis*2)+((i&(1<<1)) != 0 ? tileSize : 0),
                        zoomThis+1,
                        x0*2,
                        y0*2,
                        x1*2,
                        y1*2,
                        preferredZoom,
                        tileController
                    );
                }

                // the descendents we recursed into should have, in all cases, claimed canonicalism at a level lower
                // than this node, so if we did have canonicalism we now need to relinquish it during the unwind
                this.canonicalMask = null;
                this.belowCanonical = false;
            } else {
                Main.debug("Tile "+zoomThis+"/"+(xThis/tileSize)+"/"+(yThis/tileSize)+": drawing to\n");
                // this is a node we should be drawing to - it should be canonical or belowCanonical
                if (this.belowCanonical) {
                    // claim canonicalism for this node
                    this.canonicalMask = this.getMask(true, tileController);
                    this.belowCanonical = false;
                }

                if (this.canonicalMask != tileController.getFullMask()) {  // else drawing this will make no difference
                    Graphics2D g;
                    if (this.canonicalMask == tileController.getEmptyMask()) {
                        // we can't write to this mask - allocate another one
                        this.canonicalMask = this.newBufferedImage(tileController);
                        this.mask = new SoftReference<BufferedImage>(this.canonicalMask);

                        // clear it
                        g = this.canonicalMask.createGraphics();
                        g.setBackground(new Color(0,0,0,0));
                        g.clearRect(0, 0, tileSize, tileSize);
                    } else {
                        g = this.canonicalMask.createGraphics();
                    }

                    // draw.
                    g.setPaint(new Color(255,255,255,255));
                    g.translate(-xThis, -yThis);
                    g.fill(new Rectangle(
                        (int)Math.round(x0),
                        (int)Math.round(y0),
                        (int)Math.round(x1-x0),
                        (int)Math.round(y1-y0)
                    ));

                    // mark ancestors & descendants dirty
                    this.dirtyAncestors(false);
                    this.dirtyDescendants(false);
                }
            }
        }
    }

    public void markRectSeen(Bounds bbox, double minTilesAcross, MarkSeenTileController tileController) {
        assert tileController.getQuadTreeRWLock().isWriteLockedByCurrentThread();
        // *should* only be called on root node, right?
        assert this.parent == null;

        OsmMercator merc = new OsmMercator(tileController.getTileSource().getTileSize());

        double x0 = merc.lonToX(bbox.getMinLon(), 0);
        double y0 = merc.latToY(bbox.getMaxLat(), 0);
        double x1 = merc.lonToX(bbox.getMaxLon(), 0);
        double y1 = merc.latToY(bbox.getMinLat(), 0);

        double longSideLen = (x1-x0) > (y1-y0) ? (x1-x0) : (y1-y0);
        // calculate the factor that the longSideLen would have to be multiplied by to get it to occupy minTilesAcross
        // tiles
        double factor = minTilesAcross*tileController.getTileSource().getTileSize()/longSideLen;
        // now calculate how many zoom levels this would equate to
        int preferredZoom = (int) Math.ceil(Math.log(factor)/Math.log(2));

        // calculate "snapped" versions of these coordinates so that we don't get discontinuities between different
        // zoom levels able to render them with different precision.
        int preferredZoomFactor = 1<<preferredZoom;
        double x0s = Math.rint(x0*preferredZoomFactor)/preferredZoomFactor;
        double x1s = Math.rint(x1*preferredZoomFactor)/preferredZoomFactor;
        double y0s = Math.rint(y0*preferredZoomFactor)/preferredZoomFactor;
        double y1s = Math.rint(y1*preferredZoomFactor)/preferredZoomFactor;

        if (x0s == x1s || y0s == y1s) {
            throw new RuntimeException("Proposed rect has such extreme aspect ratio that it would be zero-width at preferredZoom");
        }

        this.markRectSeenInner(
            0,
            0,
            0,
            x0s,
            y0s,
            x1s,
            y1s,
            preferredZoom,
            tileController
        );
    }

    private void checkIntegrityInner(boolean recBelowCanonical, QuadTreeNode recParent) {
        assert this.parent == recParent;
        assert this.belowCanonical == recBelowCanonical;
        if (this.belowCanonical) {
            assert this.canonicalMask == null;
        }
        for (int i=0; i<this.children.length; i++) {
            QuadTreeNode child = this.getChild(i, false);
            if (child == null) {
                assert !this.isAboveCanonical();
            } else {
                child.checkIntegrityInner(this.belowCanonical || (this.canonicalMask != null), this);
            }
        }
    }

    public void checkIntegrity() {
        this.checkIntegrityInner(false, null);
    }
}
