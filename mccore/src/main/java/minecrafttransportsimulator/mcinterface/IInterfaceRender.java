package minecrafttransportsimulator.mcinterface;

import java.io.InputStream;

import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.guis.components.GUIComponentItem;
import minecrafttransportsimulator.rendering.GIFParser.ParsedGIF;
import minecrafttransportsimulator.rendering.RenderableObject;

/**
 * Interface for the various MC rendering engines.  This class has functions for
 * binding textures, changing lightmap statuses, etc.
 *
 * @author don_bruce
 */
public interface IInterfaceRender {

    /**
     * Returns a 4-float array for the block break texture at the passed-in position in the passed-in world.
     */
    float[] getBlockBreakTexture(AWrapperWorld world, Point3D position);

    /**
     * Returns a 4-float array for the default block texture.  This doesn't take into account world-state.
     */
    float[] getDefaultBlockTexture(String name);

    /**
     * Returns a stream of the texture specified.  This can vary depending on what texture packs are loaded!
     */
    InputStream getTextureStream(String name);

    /**
     * Renders the item model for the passed-in component.  Only
     * renders the item model: does not render text for counts.
     */
    void renderItemModel(GUIComponentItem component);

    /**
     * Renders the vertices stored in the passed-in {@link RenderableObject}.
     * If the vertices should be cached per {@link RenderableObject#cacheVertices},
     * then they are done so and a pointer-index is stored into {@link RenderableObject#cachedVertexIndex}.
     * {@link RenderableObject#vertices}.
     * If the object is ever deleted, then {@link #deleteVertices(RenderableObject, Object)}
     * should be called to free up the respective GPU memory.  The object parameter is for
     * rendering a cached vertex set on an object, since objects may have assigned buffers.
     * The value of this parameter is not used otherwise.
     */
    void renderVertices(RenderableObject object, Object objectAssociatedTo);

    /**
     * Deletes the cached vertices associated with the specified {@link RenderableObject}.
     * The object parameter is required when vertex caching is used.
     */
    void deleteVertices(RenderableObject object, Object objectAssociatedTo);

    /**
     * Binds a URL texture to a stream containing an image.  Pass in a null stream to bind the missing texture to this URL.
     * Returns true if the texture was bound, false if it couldn't be.
     */
    boolean bindURLTexture(String textureURL, InputStream strea);

    /**
     * Binds a URL GIF that was downloaded.
     * Returns true if the texture was bound, false if it couldn't be.
     */
    boolean bindURLGIF(String textureURL, ParsedGIF gif);

    /**
     * Returns an integer that represents the lighting state at the position.
     * This value is version-dependent, and should be stored in {@link RenderableObject#worldLightValue}
     */
    int getLightingAtPosition(Point3D position);

    /**
     * Returns true if bounding boxes should be rendered.
     */
    boolean shouldRenderBoundingBoxes();
}
