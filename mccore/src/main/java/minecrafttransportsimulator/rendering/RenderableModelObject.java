package minecrafttransportsimulator.rendering;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import minecrafttransportsimulator.baseclasses.AnimationSwitchbox;
import minecrafttransportsimulator.baseclasses.ColorRGB;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TransformationMatrix;
import minecrafttransportsimulator.entities.components.AEntityD_Definable;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.jsondefs.JSONAnimatedObject;
import minecrafttransportsimulator.jsondefs.JSONLight;
import minecrafttransportsimulator.jsondefs.JSONLight.JSONLightBlendableComponent;
import minecrafttransportsimulator.jsondefs.JSONText;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.rendering.GIFParser.ParsedGIF;
import minecrafttransportsimulator.systems.ConfigSystem;

/**
 * This class represents an object that can be rendered from a model.  This object is a set of
 * faces that are rendered during the main rendering routine.  Various transforms may be performed on
 * this object via the various rendering classes.  These transforms are applied to the mesh prior
 * to rendering, either manipulating the mesh directly, or manipulating the OpenGL state.
 *
 * @author don_bruce
 */
public class RenderableModelObject {
    protected final String modelLocation;
    public final RenderableObject object;
    private final boolean isWindow;
    private final boolean isOnlineTexture;
    private final RenderableObject interiorWindowObject;
    private RenderableObject colorObject;
    private RenderableObject coverObject;
    private final Map<JSONLight, RenderableObject> flareObjects = new HashMap<>();
    private final Map<JSONLight, RenderableObject> beamObjects = new HashMap<>();

    /**
     * Map of tread points, keyed by the model the tread is pathing about, then the part slot, then the spacing of the tread.
     * This can be shared for two different treads of the same spacing as they render the same.
     **/
    private static final Map<String, Map<Integer, Map<Float, List<Double[]>>>> treadPoints = new HashMap<>();
    private static final TransformationMatrix treadPathBaseTransform = new TransformationMatrix();
    private static final RotationMatrix treadRotation = new RotationMatrix();
    private static final float COLOR_OFFSET = RenderableObject.Z_BUFFER_OFFSET;
    private static final float FLARE_OFFSET = COLOR_OFFSET + RenderableObject.Z_BUFFER_OFFSET;
    private static final float COVER_OFFSET = FLARE_OFFSET + RenderableObject.Z_BUFFER_OFFSET;
    private static final float BEAM_OFFSET = -0.15F;
    private static final int BEAM_SEGMENTS = 40;

    private final Set<String> downloadingTextures = new HashSet<>();
    private final Set<String> downloadedTextures = new HashSet<>();
    private static final Map<String, String> erroredTextures = new HashMap<>();
    private static boolean errorTextureBound;

    public RenderableModelObject(String modelLocation, RenderableObject object) {
        super();
        this.modelLocation = modelLocation;
        this.isWindow = object.name.toLowerCase(Locale.ROOT).contains(AModelParser.WINDOW_OBJECT_NAME);
        this.isOnlineTexture = object.name.toLowerCase(Locale.ROOT).startsWith(AModelParser.ONLINE_TEXTURE_OBJECT_NAME) || object.name.toLowerCase(Locale.ROOT).endsWith(AModelParser.ONLINE_TEXTURE_OBJECT_NAME);

        //If we are a window, split the model into two parts.  The first will be the exterior which will
        //be our normal model, the second will be a new, inverted, interior model.
        if (isWindow) {
            this.object = new RenderableObject(object.name, "mts:textures/rendering/glass.png", object.color, object.vertices, false);
            this.object.normalizeUVs();
            this.interiorWindowObject = new RenderableObject(object.name + "_interior", "mts:textures/rendering/glass.png", object.color, FloatBuffer.allocate(object.vertices.capacity()), false);
            float[] vertexSet = new float[8];
            for (int i = object.vertices.capacity() - 8; i >= 0; i -= 8) {
                object.vertices.get(vertexSet);
                interiorWindowObject.vertices.position(i);
                interiorWindowObject.vertices.put(vertexSet);
            }
            object.vertices.rewind();
            interiorWindowObject.vertices.position(0);
            interiorWindowObject.vertices.limit(object.vertices.limit());
        } else {
            this.object = object;
            this.interiorWindowObject = null;
        }

        //If we are a light object, create color and cover points.
        //We may not use these, but it saves on processing later as we don't need to re-parse the model.
        if (object.name.startsWith("&")) {
            colorObject = generateColors(object);
            coverObject = generateCovers(object);
        }

        //Bind the error texture if we haven't already.
        if (!errorTextureBound) {
            InterfaceManager.renderingInterface.bindURLTexture("ERROR", null);
            errorTextureBound = true;
        }
    }

    /**
     * Renders this object, applying any transforms that need to happen.  This method also
     * renders any objects that depend on this object's transforms after rendering.
     */
    public void render(AEntityD_Definable<?> entity, TransformationMatrix transform, boolean blendingEnabled, float partialTicks) {
        //Do pre-render checks based on the object we are rendering.
        //This may block rendering if there are false visibility transforms or the wrong render pass.
        JSONAnimatedObject objectDef = entity.animatedObjectDefinitions.get(object.name);
        JSONLight lightDef = entity.lightObjectDefinitions.get(object.name);
        if (shouldRender(entity, objectDef, lightDef, blendingEnabled, partialTicks)) {
            AnimationSwitchbox switchbox = entity.animatedObjectSwitchboxes.get(object.name);
            if (objectDef == null || objectDef.blendedAnimations || switchbox == null || switchbox.runSwitchbox(partialTicks, false)) {
                //If we are a blended animation object, run the switchbox.
                //We won't have done this in the IF statement.
                if (objectDef != null && objectDef.blendedAnimations && switchbox != null) {
                    switchbox.runSwitchbox(partialTicks, false);
                }

                float lightLevel = lightDef != null ? entity.lightBrightnessValues.get(lightDef) : 0;
                object.transform.set(transform);

                //Apply switchbox transform, if we have one.
                if (switchbox != null) {
                    object.transform.multiply(switchbox.netMatrix);
                }

                //If we are a online texture, bind that one rather than our own.
                if (isOnlineTexture) {
                    //Get the texture from the text objects of the entity.
                    //If we don't have anything set, we just use the existing texture.
                    for (Entry<JSONText, String> textEntry : entity.text.entrySet()) {
                        JSONText textDef = textEntry.getKey();
                        if (textDef.fieldName != null && object.name.contains(textDef.fieldName)) {
                            String textValue = entity.text.get(textDef);
                            if (erroredTextures.containsKey(textValue)) {
                                //Error in texture downloading, set fault data before continuing.
                                textEntry.setValue(erroredTextures.get(textValue));
                            }
                            if (textValue.startsWith("ERROR")) {
                                //Texture didn't download, set to error texture.
                                object.texture = "ERROR";
                            } else if (downloadedTextures.contains(textValue)) {
                                //Good to render, set texture to object and go.
                                object.texture = textValue;
                            } else if (downloadingTextures.contains(textValue)) {
                                //Still downloading, skip rendering.
                                return;
                            } else if (textValue.isEmpty()) {
                                //Don't render since we don't have any text bound here.
                                return;
                            } else {
                                //No data at all.  Need to queue up a downloader for this texture.  Do so and skip rendering until it completes.
                                new ConnectorThread(textValue, this).run();
                                downloadingTextures.add(textValue);
                                return;
                            }
                            break;
                        }
                    }
                } else if (!isWindow) {
                    //Set our standard texture, provided we're not a window.
                    object.texture = entity.getTexture();
                }

                //If we are a light, get the actual light level as calculated.
                //We do this here as there's no reason to calculate this if we're not gonna render.
                if (lightDef != null) {
                    lightLevel = entity.lightBrightnessValues.get(lightDef);
                    if (lightDef.isElectric && entity instanceof EntityVehicleF_Physics) {
                        //Light start dimming at 10V, then go dark at 3V.
                        double electricPower = ((EntityVehicleF_Physics) entity).electricPower;
                        if (electricPower < 3) {
                            lightLevel = 0;
                        } else if (electricPower < 10) {
                            lightLevel *= (electricPower - 3) / 7D;
                        }
                    }
                }

                if (entity instanceof PartGroundDevice && ((PartGroundDevice) entity).definition.ground.isTread && !((PartGroundDevice) entity).isSpare) {
                    //Active tread.  Do tread-path rendering instead of normal model.
                    if (!blendingEnabled) {
                        object.setLighting(entity.worldLightValue, false, false);
                        doTreadRendering((PartGroundDevice) entity, partialTicks);
                    }
                } else {
                    //Set object states and render.
                    if (blendingEnabled && lightDef != null && lightLevel > 0 && lightDef.isBeam && entity.shouldRenderBeams()) {
                        //Model that's actually a beam, render it with beam lighting/blending. 
                        object.setLighting(entity.worldLightValue, ConfigSystem.client.renderingSettings.brightLights.value, true);
                        object.setBlending(ConfigSystem.client.renderingSettings.blendedLights.value);
                        object.setAlpha(Math.min((1 - entity.world.getLightBrightness(entity.position, false)) * lightLevel, 1));
                        object.render(entity);
                    } else if (blendingEnabled == object.isTranslucent) {
                        //Either solid texture on solid pass, or translucent texture on blended pass.
                        //Need to disable light-mapping from daylight if we are a light-up texture.
                        object.setLighting(entity.worldLightValue, ConfigSystem.client.renderingSettings.brightLights.value && lightDef != null && lightLevel > 0 && !lightDef.emissive && !lightDef.isBeam, false);
                        //Also adjust alpha to visibility, if we are on a blended pass and have a switchbox.
                        if (blendingEnabled && objectDef != null && objectDef.blendedAnimations && switchbox != null && switchbox.lastVisibilityClock != null) {
                            if (switchbox.lastVisibilityValue < switchbox.lastVisibilityClock.animation.clampMin) {
                                object.setAlpha(0);
                            } else if (switchbox.lastVisibilityValue >= switchbox.lastVisibilityClock.animation.clampMax) {
                                //Need >= here instead of above for things where min/max clamps are equal.
                                object.setAlpha(1);
                            } else {
                                object.setAlpha((float) (switchbox.lastVisibilityValue - switchbox.lastVisibilityClock.animation.clampMin) / (switchbox.lastVisibilityClock.animation.clampMax - switchbox.lastVisibilityClock.animation.clampMin));
                            }
                        }
                        object.render(entity);
                        if (interiorWindowObject != null && ConfigSystem.client.renderingSettings.innerWindows.value) {
                            interiorWindowObject.setLighting(object.worldLightValue, false, false);
                            interiorWindowObject.transform.set(object.transform);
                            interiorWindowObject.render(entity);
                        }
                    }

                    //Check if we are a light that's not a beam.  If so, do light-specific rendering.
                    if (lightDef != null && !lightDef.isBeam) {
                        doLightRendering(entity, lightDef, lightLevel, entity.lightColorValues.get(lightDef), blendingEnabled);
                    }

                    //Render text on this object.  Only do this on the solid pass.
                    if (!blendingEnabled) {
                        for (Entry<JSONText, String> textEntry : entity.text.entrySet()) {
                            JSONText textDef = textEntry.getKey();
                            if (object.name.equals(textDef.attachedTo)) {
                                RenderText.draw3DText(textEntry.getValue(), entity, object.transform, textDef, false);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Call to destroy this renderable object.  This should be done prior to re-parsing the model
     * as it allows for the freeing of OpenGL resources.
     */
    public void destroy(AEntityD_Definable<?> entity) {
        object.destroy(entity);
        treadPoints.remove(modelLocation);
    }

    private boolean shouldRender(AEntityD_Definable<?> entity, JSONAnimatedObject objectDef, JSONLight lightDef, boolean blendingEnabled, float partialTicks) {
        //Translucent only renders on blended pass.
        if (object.isTranslucent && !blendingEnabled) {
            return false;
        }
        //Block windows if we have them disabled.
        if (isWindow && !ConfigSystem.client.renderingSettings.renderWindows.value) {
            return false;
        }
        //If the light only has solid components, and we aren't translucent, don't render on the blending pass.
        if (lightDef != null && blendingEnabled && !object.isTranslucent && !lightDef.emissive && !lightDef.isBeam && (lightDef.blendableComponents == null || lightDef.blendableComponents.isEmpty())) {
            return false;
        }
        //If we have an applyAfter, and that object isn't being rendered, don't render us either.
        if (objectDef != null) {
            if (objectDef.applyAfter != null) {
                AnimationSwitchbox switchbox = entity.animatedObjectSwitchboxes.get(objectDef.applyAfter);
                if (switchbox == null) {
                    throw new IllegalArgumentException("Was told to applyAfter the object " + objectDef.applyAfter + " on " + entity.definition.packID + ":" + entity.definition.systemName + " for the object " + object.name + ", but there aren't any animations to applyAfter!");
                }
                return switchbox.runSwitchbox(partialTicks, false);
            }
        }

        return true;
    }

    private void doTreadRendering(PartGroundDevice tread, float partialTicks) {
        String treadPathModel = tread.entityOn.definition.getModelLocation(tread.entityOn.subDefinition);
        Map<Integer, Map<Float, List<Double[]>>> treadPointsMap = treadPoints.get(treadPathModel);
        if (treadPointsMap == null) {
            treadPointsMap = new HashMap<>();
        }
        Map<Float, List<Double[]>> treadPointsSubMap = treadPointsMap.get(tread.placementSlot);
        if (treadPointsSubMap == null) {
            treadPointsSubMap = new HashMap<>();
        }
        List<Double[]> points = treadPointsSubMap.get(tread.definition.ground.spacing);

        if (points == null) {
            points = generateTreads(tread.entityOn, treadPathModel, treadPointsSubMap, tread);
            treadPointsSubMap.put(tread.definition.ground.spacing, points);
            treadPointsMap.put(tread.placementSlot, treadPointsSubMap);
            treadPoints.put(treadPathModel, treadPointsMap);
        }

        //Render the treads along their points.
        //We manually set point 0 here due to the fact it's a joint between two differing angles.
        //We also need to translate to that point to start rendering as we're currently at 0,0,0.
        //For each remaining point, we only translate the delta of the point.
        float treadLinearPosition = (float) (tread.getRawVariableValue("ground_rotation", partialTicks) / 360D);
        float treadMovementPercentage = (treadLinearPosition % tread.definition.ground.spacing) / tread.definition.ground.spacing;
        if (treadMovementPercentage < 0) {
            ++treadMovementPercentage;
        }
        Double[] point;
        Double[] nextPoint;
        double yDelta;
        double zDelta;
        double angleDelta;

        //Tread rendering is done via the thing the tread is on, which will assume the part is centered at 0, 0, 0.
        //We need to undo the offset of the tread part for this routine.
        if (!(tread.entityOn instanceof APart)) {
            object.transform.applyTranslation(0, -tread.localOffset.y, -tread.localOffset.z);
        }

        //Add initial translation for the first point
        point = points.get(0);
        object.transform.applyTranslation(0, point[0], point[1]);

        //Get cycle index for later.
        boolean[] renderIndexes = null;
        if (tread.definition.ground.treadOrder != null) {
            int treadCycleCount = tread.definition.ground.treadOrder.size();
            double treadCycleTotalDistance = treadCycleCount * tread.definition.ground.spacing;
            int treadCycleIndex = (int) Math.floor(treadCycleCount * ((treadLinearPosition % treadCycleTotalDistance) / treadCycleTotalDistance));
            if (treadCycleIndex < 0) {
                //Need to handle negatives if we only go backwards.
                treadCycleIndex += treadCycleCount;
            }
            renderIndexes = new boolean[treadCycleCount];
            for (int i = 0; i < treadCycleCount; ++i) {
                String treadObject = tread.definition.ground.treadOrder.get(i);
                renderIndexes[(i + treadCycleIndex) % treadCycleCount] = treadObject.equals(object.name);
            }
        }

        //Now transform all points.
        for (int i = 0; i < points.size() - 1; ++i) {
            //Update variables.
            //If we're at the last point, set the next point to the first point.
            //Also adjust angle delta, as it'll likely be almost 360 and needs to be adjusted for this.
            point = points.get(i);
            if (i == points.size() - 1) {
                nextPoint = points.get(0);
                angleDelta = (nextPoint[2] + 360) - point[2];
            } else {
                nextPoint = points.get(i + 1);
                angleDelta = nextPoint[2] - point[2];
            }
            yDelta = nextPoint[0] - point[0];
            zDelta = nextPoint[1] - point[1];

            //If our angle delta is greater than 180, we can assume that we're inverted.
            //This happens when we cross the 360 degree rotation barrier.
            if (angleDelta > 180) {
                angleDelta -= 360;
            } else if (angleDelta < -180) {
                angleDelta += 360;
            }

            //Check if we should render this object as a link in this position.
            //This is normally true, but for patterns we need to only render in specific spots.
            if (renderIndexes != null && !renderIndexes[i % renderIndexes.length]) {
                object.transform.applyTranslation(0, yDelta, zDelta);
                continue;
            }

            //Translate to the current position of the tread based on the percent it has moved.
            //This is determined by partial ticks and actual tread position.
            //Once there, render the tread.  Then translate the remainder of the way to prepare
            //to render the next tread.
            object.transform.applyTranslation(0, yDelta * treadMovementPercentage, zDelta * treadMovementPercentage);

            //If there's no rotation to the point, and no delta between points, don't do rotation.  That's just extra math.
            //Do note that the model needs to be rotated 180 on the X-axis due to all our points
            //assuming a YZ coordinate system with 0 degrees rotation being in +Y (just how the math comes out).
            //This is why 180 is added to all points cached in the operations above.
            if (point[2] != 0 || angleDelta != 0) {
                //We can't use a running rotation here as we'll end up translating in the rotated
                //coordinate system.  To combat this, we translate like normal, but then push a
                //stack and rotate prior to rendering.  This keeps us from having to do another
                //rotation to get the old coordinate system back.
                treadPathBaseTransform.set(object.transform);
                treadRotation.setToAxisAngle(1, 0, 0, point[2] + angleDelta * treadMovementPercentage);
                object.transform.applyRotation(treadRotation);
                object.render(tread);
                object.transform.set(treadPathBaseTransform);
            } else {
                //Just render as normal as we didn't rotate.
                object.render(tread);
            }

            //Add remaining translation.
            object.transform.applyTranslation(0, yDelta * (1 - treadMovementPercentage), zDelta * (1 - treadMovementPercentage));
        }
    }

    private void doLightRendering(AEntityD_Definable<?> entity, JSONLight lightDef, float lightLevel, ColorRGB color, boolean blendingEnabled) {
        if (blendingEnabled && lightLevel > 0 && lightDef.emissive) {
            //Light color detected on blended render pass.
            if (colorObject == null) {
                for (RenderableObject testObject : AModelParser.parseModel(modelLocation)) {
                    if (object.name.equals(testObject.name)) {
                        colorObject = generateColors(testObject);
                        break;
                    }
                }
            }

            colorObject.setLighting(object.worldLightValue, ConfigSystem.client.renderingSettings.brightLights.value, true);
            colorObject.setColor(color);
            colorObject.setAlpha(lightLevel);
            colorObject.transform.set(object.transform);
            colorObject.render(entity);

        }
        if (blendingEnabled && lightLevel > 0 && lightDef.blendableComponents != null && !lightDef.blendableComponents.isEmpty()) {
            //Light flares or beams detected on blended render pass.
            //First render all flares, then render all beams.
            float blendableBrightness = Math.min((1 - entity.world.getLightBrightness(entity.position, false)) * lightLevel, 1);
            if (blendableBrightness > 0) {
                RenderableObject flareObject = flareObjects.get(lightDef);
                RenderableObject beamObject = beamObjects.get(lightDef);
                if (flareObject == null && beamObject == null) {
                    List<JSONLightBlendableComponent> flareDefs = new ArrayList<>();
                    List<JSONLightBlendableComponent> beamDefs = new ArrayList<>();
                    for (JSONLightBlendableComponent component : lightDef.blendableComponents) {
                        if (component.flareHeight > 0) {
                            flareDefs.add(component);
                        }
                        if (component.beamDiameter > 0) {
                            beamDefs.add(component);
                        }
                    }
                    if (!flareDefs.isEmpty()) {
                        flareObjects.put(lightDef, flareObject = generateFlares(flareDefs));
                    }
                    if (!beamDefs.isEmpty()) {
                        beamObjects.put(lightDef, beamObject = generateBeams(beamDefs));
                    }
                }

                //Render all flares.
                if (flareObject != null) {
                    flareObject.isTranslucent = true;
                    flareObject.setLighting(object.worldLightValue, ConfigSystem.client.renderingSettings.brightLights.value, true);
                    flareObject.setColor(color);
                    flareObject.setAlpha(blendableBrightness);
                    flareObject.transform.set(object.transform);
                    flareObject.render(entity);
                }

                //Render all beams.
                if (beamObject != null && entity.shouldRenderBeams()) {
                    beamObject.isTranslucent = true;
                    beamObject.setLighting(object.worldLightValue, ConfigSystem.client.renderingSettings.brightLights.value, true);
                    beamObject.setBlending(ConfigSystem.client.renderingSettings.blendedLights.value);
                    beamObject.setColor(color);
                    beamObject.setAlpha(blendableBrightness);
                    beamObject.transform.set(object.transform);
                    beamObject.render(entity);
                }
            }
        }
        if (!blendingEnabled && lightDef.covered) {
            //Light cover detected on solid render pass.
            if (coverObject == null) {
                for (RenderableObject testObject : AModelParser.parseModel(modelLocation)) {
                    if (object.name.equals(testObject.name)) {
                        coverObject = generateCovers(testObject);
                        break;
                    }
                }
            }

            coverObject.setLighting(object.worldLightValue, ConfigSystem.client.renderingSettings.brightLights.value && lightLevel > 0, false);
            coverObject.transform.set(object.transform);
            coverObject.render(entity);
        }
    }

    private static RenderableObject generateColors(RenderableObject parsedObject) {
        //Make a duplicate set of vertices with an offset for the color rendering.
        RenderableObject offsetObject = new RenderableObject("color", "mts:textures/rendering/light.png", new ColorRGB(), FloatBuffer.allocate(parsedObject.vertices.capacity()), false);
        offsetObject.isTranslucent = true;
        float[] vertexData = new float[8];
        while (parsedObject.vertices.hasRemaining()) {
            parsedObject.vertices.get(vertexData);
            offsetObject.vertices.put(vertexData, 0, 5);
            offsetObject.vertices.put(vertexData[5] + vertexData[0] * COLOR_OFFSET);
            offsetObject.vertices.put(vertexData[6] + vertexData[1] * COLOR_OFFSET);
            offsetObject.vertices.put(vertexData[7] + vertexData[2] * COLOR_OFFSET);
        }
        parsedObject.vertices.rewind();
        offsetObject.normalizeUVs();
        offsetObject.vertices.flip();
        return offsetObject;
    }

    private static RenderableObject generateCovers(RenderableObject parsedObject) {
        //Make a duplicate set of vertices with an offset for the cover rendering.
        RenderableObject offsetObject = new RenderableObject("cover", "mts:textures/rendering/glass.png", parsedObject.color, FloatBuffer.allocate(parsedObject.vertices.capacity()), false);
        float[] vertexData = new float[8];
        while (parsedObject.vertices.hasRemaining()) {
            parsedObject.vertices.get(vertexData);
            offsetObject.vertices.put(vertexData, 0, 5);
            offsetObject.vertices.put(vertexData[5] + vertexData[0] * COVER_OFFSET);
            offsetObject.vertices.put(vertexData[6] + vertexData[1] * COVER_OFFSET);
            offsetObject.vertices.put(vertexData[7] + vertexData[2] * COVER_OFFSET);
        }
        parsedObject.vertices.rewind();
        offsetObject.normalizeUVs();
        offsetObject.vertices.flip();
        return offsetObject;
    }

    private static RenderableObject generateFlares(List<JSONLightBlendableComponent> flareDefs) {
        //6 vertices per flare due to triangle rendering.
        RenderableObject flareObject = new RenderableObject("flares", "mts:textures/rendering/lensflare.png", new ColorRGB(), FloatBuffer.allocate(flareDefs.size() * 6 * 8), false);
        flareObject.isTranslucent = true;
        for (JSONLightBlendableComponent flareDef : flareDefs) {
            //Get the matrix  that is needed to rotate points to the normalized vector.
            RotationMatrix rotation = new RotationMatrix().setToVector(flareDef.axis, false);
            Point3D vertexOffset = new Point3D();
            Point3D centerOffset = flareDef.axis.copy().scale(FLARE_OFFSET).add(flareDef.pos);
            for (int j = 0; j < 6; ++j) {
                float[] newVertex = new float[8];
                //Get the current UV points.
                switch (j) {
                    case (0):
                    case (3)://Bottom-right
                        newVertex[3] = 1.0F;
                        newVertex[4] = 1.0F;
                        break;
                    case (1)://Top-right
                        newVertex[3] = 1.0F;
                        newVertex[4] = 0.0F;
                        break;
                    case (2):
                    case (4)://Top-left
                        newVertex[3] = 0.0F;
                        newVertex[4] = 0.0F;
                        break;
                    case (5)://Bottom-left
                        newVertex[3] = 0.0F;
                        newVertex[4] = 1.0F;
                        break;
                }

                //Based on the UVs and the axis for the flare, calculate the vertices.
                vertexOffset.x = newVertex[3] == 0.0 ? -flareDef.flareWidth / 2D : flareDef.flareWidth / 2D;
                vertexOffset.y = newVertex[4] == 0.0 ? flareDef.flareHeight / 2D : -flareDef.flareHeight / 2D;
                vertexOffset.z = 0;
                vertexOffset.rotate(rotation).add(centerOffset);
                newVertex[5] = (float) vertexOffset.x;
                newVertex[6] = (float) vertexOffset.y;
                newVertex[7] = (float) vertexOffset.z;

                //Set normals to the normal axis in the JSON.
                newVertex[0] = (float) flareDef.axis.x;
                newVertex[1] = (float) flareDef.axis.y;
                newVertex[2] = (float) flareDef.axis.z;

                //Add the actual vertex.
                flareObject.vertices.put(newVertex);
            }
        }
        flareObject.vertices.flip();
        return flareObject;
    }

    private static RenderableObject generateBeams(List<JSONLightBlendableComponent> beamDefs) {
        //3 vertices per cone-face, each share the same center point.
        //Number of cone faces is equal to the number of segments for beams.
        //We render two beams.  One inner and one outer.
        RenderableObject beamObject = new RenderableObject("beams", "mts:textures/rendering/lightbeam.png", new ColorRGB(), FloatBuffer.allocate(beamDefs.size() * 2 * BEAM_SEGMENTS * 3 * 8), false);
        beamObject.isTranslucent = true;
        for (JSONLightBlendableComponent beamDef : beamDefs) {
            //Get the matrix that is needed to rotate points to the normalized vector.
            RotationMatrix rotation = new RotationMatrix().setToVector(beamDef.axis, false);
            Point3D vertexOffset = new Point3D();
            Point3D centerOffset = beamDef.axis.copy().scale(BEAM_OFFSET).add(beamDef.pos);
            //Go from negative to positive to render both beam-faces in the same loop.
            for (int j = -BEAM_SEGMENTS; j < BEAM_SEGMENTS; ++j) {
                for (int k = 0; k < 3; ++k) {
                    float[] newVertex = new float[8];
                    //Get the current UV points.
                    //Point 0 is always the center of the beam, 1 and 2 are the outer points.
                    switch (k % 3) {
                        case (0):
                            newVertex[3] = 0.0F;
                            newVertex[4] = 0.0F;
                            break;
                        case (1):
                            newVertex[3] = 0.0F;
                            newVertex[4] = 1.0F;
                            break;
                        case (2):
                            newVertex[3] = 1.0F;
                            newVertex[4] = 1.0F;
                            break;
                    }

                    //Based on the UVs and the axis for the beam, calculate the vertices.
                    double currentAngleRad;
                    if (j < 0) {
                        currentAngleRad = newVertex[3] == 0.0F ? 2D * Math.PI * ((j + 1) / (double) BEAM_SEGMENTS) : 2D * Math.PI * (j / (double) BEAM_SEGMENTS);
                    } else {
                        currentAngleRad = newVertex[3] == 0.0F ? 2D * Math.PI * (j / (double) BEAM_SEGMENTS) : 2D * Math.PI * ((j + 1) / (double) BEAM_SEGMENTS);
                    }
                    if (newVertex[4] == 0.0) {
                        vertexOffset.set(0, 0, 0);
                    } else {
                        vertexOffset.x = beamDef.beamDiameter / 2F * Math.cos(currentAngleRad);
                        vertexOffset.y = beamDef.beamDiameter / 2F * Math.sin(currentAngleRad);
                        vertexOffset.z = beamDef.beamLength;
                    }
                    vertexOffset.rotate(rotation).add(centerOffset);
                    newVertex[5] = (float) vertexOffset.x;
                    newVertex[6] = (float) vertexOffset.y;
                    newVertex[7] = (float) vertexOffset.z;

                    //Don't care about normals for beam rendering as it's a blending face, so we just set them to 0.
                    newVertex[0] = 0F;
                    newVertex[1] = 0F;
                    newVertex[2] = 0F;

                    //Add the actual vertex.
                    beamObject.vertices.put(newVertex);
                }
            }
        }
        beamObject.vertices.flip();
        return beamObject;
    }

    private static <TreadEntity extends AEntityD_Definable<?>> List<Double[]> generateTreads(TreadEntity entityTreadAttachedTo, String treadPathModel, Map<Float, List<Double[]>> treadPointsMap, PartGroundDevice tread) {
        //If we don't have the deltas, calculate them based on the points of the rollers defined in the JSON.			
        //Search through rotatable parts on the model and grab the rollers.
        List<RenderableObject> parsedModel = AModelParser.parseModel(entityTreadAttachedTo.definition.getModelLocation(entityTreadAttachedTo.definition.definitions.get(0)));
        List<TreadRoller> rollers = new ArrayList<>();
        if (tread.placementDefinition.treadPath == null) {
            throw new IllegalArgumentException("No tread path found for part slot on " + entityTreadAttachedTo + "!");
        }
        for (String rollerName : tread.placementDefinition.treadPath) {
            boolean foundRoller = false;
            for (RenderableObject modelObject : parsedModel) {
                if (modelObject.name.equals(rollerName)) {
                    rollers.add(new TreadRoller(modelObject));
                    foundRoller = true;
                    break;
                }
            }
            if (!foundRoller) {
                throw new IllegalArgumentException("Could not create tread path for " + entityTreadAttachedTo + " Due to missing roller " + rollerName + " in the model!");
            }
        }

        //Now that we have all the rollers, we can start calculating points.
        //First calculate the endpoints on the rollers by calling the calculation method.
        for (int i = 0; i < rollers.size(); ++i) {
            if (i < rollers.size() - 1) {
                rollers.get(i).calculateEndpoints(rollers.get(i + 1));
            } else {
                rollers.get(i).calculateEndpoints(rollers.get(0));
            }
        }

        //We need to ensure the endpoints are all angle-aligned.
        //It's possible to have a start angle of -181 and end angle of
        //181, which is really just 2 degress of angle (179-181).
        //To do this, we set the star angle of roller 1 to be 180, 
        //or downward-facing.  From there, we add angles to align things.
        //At the end, we should have a total angle of 540, or 180 + 360.
        rollers.get(0).setEndAngle(180);
        for (int i = 1; i < rollers.size(); ++i) {
            TreadRoller roller = rollers.get(i);
            TreadRoller priorRoller = rollers.get(i - 1);

            //Set the start angle to the end angle of the prior roller, then check for validity.
            roller.startAngle = i == 1 ? 180 : priorRoller.endAngle;

            //Roller angle delta  should be within -30-330 degrees.
            //Positive angles are standard, but negative are possible for concave routing points.
            while (roller.endAngle < roller.startAngle - 30) {
                roller.endAngle += 360;
            }
            while (roller.endAngle > roller.startAngle + 330) {
                roller.endAngle -= 360;
            }

            if (roller.endAngle < roller.startAngle) {
                //We have a concave roller.  Set our start and end angle to the midpoint of their current values.
                //This aligns the point on the roller to the center of concavity.
                double midPoint = roller.endAngle + (roller.startAngle - roller.endAngle) / 2D;
                roller.startAngle = midPoint;
                roller.endAngle = midPoint;
            }

            //Roller angles are bound.  Set our start and end angle values.
            roller.setStartAngle(roller.startAngle);
            roller.setEndAngle(roller.endAngle);
        }
        //Set the start angle to match the end angle of the last roller, rather than the 180 we set.
        rollers.get(0).setStartAngle(rollers.get(rollers.size() - 1).endAngle);

        //Now that the endpoints are set, we can calculate the path.
        //Do this by following the start and end points at small increments.
        //First calculate the total path length, and determine the optimum spacing.
        //This is the closest value to the definition's tread spacing.
        double totalPathLength = 0;
        for (int i = 0; i < rollers.size(); ++i) {
            //Get roller and add roller path contribution.
            TreadRoller roller = rollers.get(i);
            double angleDelta = roller.endAngle - roller.startAngle;
            if (i == 0) {
                //Need to add 360 rev for angle delta, as this will be this way from the tread going around the path.
                angleDelta += 360;
            }
            totalPathLength += 2 * Math.PI * roller.radius * angleDelta / 360D;

            //Get next roller and add distance path contribution.
            //For points that start and end at an angle of around 0 (top of rollers) we add droop.
            //This is a hyperbolic function, so we need to calculate the integral value to account for the path.
            TreadRoller nextRoller = i == rollers.size() - 1 ? rollers.get(0) : rollers.get(i + 1);
            double straightPathLength = Math.hypot(nextRoller.startY - roller.endY, nextRoller.startZ - roller.endZ);
            if (tread.placementDefinition.treadDroopConstant > 0 && (roller.endAngle % 360 < 10 || roller.endAngle % 360 > 350) && (nextRoller.startAngle % 360 < 10 || nextRoller.startAngle % 360 > 350)) {
                //Catenary path length is a*singh(x/a), a is droop constant, x will be 1/2 total catenary distance due to symmetry, multiply this distance by 2 for total droop.
                totalPathLength += 2D * tread.placementDefinition.treadDroopConstant * Math.sinh((straightPathLength / 2D) / tread.placementDefinition.treadDroopConstant);
            } else {
                totalPathLength += straightPathLength;
            }
        }

        double deltaDist = tread.definition.ground.spacing + (totalPathLength % tread.definition.ground.spacing) / (totalPathLength / tread.definition.ground.spacing);
        double leftoverPathLength = 0;
        double yPoint = 0;
        double zPoint = 0;
        List<Double[]> points = new ArrayList<>();
        for (int i = 0; i < rollers.size(); ++i) {
            TreadRoller roller = rollers.get(i);
            //Follow the curve of the roller from the start and end point.
            //Do this until we don't have enough roller path left to make a point.
            //If we have any remaining path from a prior operation, we
            //need to offset our first point on the roller path to account for it.
            //It can very well be that this remainder will be more than the path length
            //of the roller.  If so, we just skip the roller entirely.
            //For the first roller we need to do some special math, as the angles will be inverted
            //For start and end due to the tread making a full 360 path.
            double currentAngle = roller.startAngle;
            double angleDelta = roller.endAngle - roller.startAngle;
            if (i == 0) {
                //Need to add 360 rev for angle delta, as this will be this way from the tread going around the path.
                angleDelta += 360;
            }
            double rollerPathLength = 2 * Math.PI * roller.radius * angleDelta / 360D;

            //Add the first point here, and add more as we follow the path.
            if (i == 0) {
                yPoint = roller.centerPoint.y + roller.radius * Math.cos(Math.toRadians(currentAngle));
                zPoint = roller.centerPoint.z + roller.radius * Math.sin(Math.toRadians(currentAngle));
                points.add(new Double[]{yPoint, zPoint, currentAngle + 180});
            }

            //If we have any leftover straight path, account for it here to keep spacing consistent.
            //We will need to interpolate the point that the straight path would have gone to, but
            //take our rotation angle into account.  Only do this if we have enough of a path to do so.
            //If not, we should just skip this roller as we can't put any points on it.
            if (deltaDist - leftoverPathLength < rollerPathLength) {
                if (leftoverPathLength > 0) {
                    //Go backwards on the roller so when we do our next operation, we align with a new point.
                    //This ensures the new point will be closer to the start of the roller than normal.
                    //Make a new point that's a specific amount of path-movement along this roller.
                    //Then increment currentAngle to account for the new point made.
                    //We use the circumference of the roller and the remaining path to find out the amount to adjust.
                    currentAngle -= 360D * leftoverPathLength / roller.circumference;
                    rollerPathLength += leftoverPathLength;
                    leftoverPathLength = 0;
                }

                while (rollerPathLength > deltaDist) {
                    //Go to and add the next point on the roller path.
                    rollerPathLength -= deltaDist;
                    currentAngle += 360D * (deltaDist / roller.circumference);
                    yPoint = roller.centerPoint.y + roller.radius * Math.cos(Math.toRadians(currentAngle));
                    zPoint = roller.centerPoint.z + roller.radius * Math.sin(Math.toRadians(currentAngle));
                    points.add(new Double[]{yPoint, zPoint, currentAngle + 180});
                }
            }

            //Done following roller, set angle to roller end angle to prevent slight FPEs.
            currentAngle = roller.endAngle;

            //If we have any leftover roller path, account for it here to keep spacing consistent.
            //We may also have leftover straight path length if we didn't do anything on a roller.
            //If we have roller length, make sure to offset it to account for the curvature of the roller.
            //If we don't do this, the line won't start at the end of the prior roller.
            //If we are on the last roller, we need to get the first roller to complete the loop.
            //For points that start and end at an angle of around 0 (top of rollers) we add droop.
            TreadRoller nextRoller = i == rollers.size() - 1 ? rollers.get(0) : rollers.get(i + 1);
            double straightPathLength = Math.hypot(nextRoller.startY - roller.endY, nextRoller.startZ - roller.endZ);
            double extraPathLength = rollerPathLength + leftoverPathLength;
            double normalizedY = (nextRoller.startY - roller.endY) / straightPathLength;
            double normalizedZ = (nextRoller.startZ - roller.endZ) / straightPathLength;
            if (tread.placementDefinition.treadDroopConstant > 0 && (roller.endAngle % 360 < 10 || roller.endAngle % 360 > 350) && (nextRoller.startAngle % 360 < 10 || nextRoller.startAngle % 360 > 350)) {
                //Catenary path length is a*singh(x/a), a is droop constant, x will be 1/2 total catenary distance due to symmetry, multiply this distance by 2 for total droop.
                double catenaryPathLength = 2D * tread.placementDefinition.treadDroopConstant * Math.sinh((straightPathLength / 2D) / tread.placementDefinition.treadDroopConstant);

                //Get the top point in Y for the tips of the catenary (1/2 the span).  We will translate the droop path down this far to make the ends line up at Y=0.
                //We then offset this value to the rollers for the actual point position.
                final double catenaryPathEdgeY = tread.placementDefinition.treadDroopConstant * Math.cosh((straightPathLength / 2D) / tread.placementDefinition.treadDroopConstant);

                double catenaryFunctionCurrent = -catenaryPathLength / 2F;
                double catenaryPointZ;
                double catenaryPointY;
                double startingCatenaryPathLength = catenaryPathLength;
                while (catenaryPathLength + extraPathLength > deltaDist) {
                    //Go to and add the next point on the catenary path.
                    if (extraPathLength > 0) {
                        catenaryFunctionCurrent += (deltaDist - extraPathLength);
                        catenaryPathLength -= (deltaDist - extraPathLength);
                        extraPathLength = 0;
                    } else {
                        catenaryFunctionCurrent += deltaDist;
                        catenaryPathLength -= deltaDist;
                    }
                    double value = catenaryFunctionCurrent / tread.placementDefinition.treadDroopConstant;
                    double arcSin = catenaryFunctionCurrent == 0.0 ? 0 : Math.log(value + Math.sqrt(value * value + 1.0));
                    double catenaryFunctionPercent = (catenaryFunctionCurrent + startingCatenaryPathLength / 2) / startingCatenaryPathLength;
                    catenaryPointZ = tread.placementDefinition.treadDroopConstant * arcSin;
                    catenaryPointY = tread.placementDefinition.treadDroopConstant * Math.cosh(catenaryPointZ / tread.placementDefinition.treadDroopConstant);
                    yPoint = roller.endY + normalizedY * catenaryFunctionPercent + catenaryPointY - catenaryPathEdgeY;
                    zPoint = roller.endZ + catenaryPointZ + straightPathLength / 2D;
                    points.add(new Double[]{yPoint, zPoint, currentAngle + 180 - Math.toDegrees(Math.asin(catenaryFunctionCurrent / tread.placementDefinition.treadDroopConstant))});
                }
                leftoverPathLength = catenaryPathLength;
            } else {
                while (straightPathLength + extraPathLength > deltaDist) {
                    //Go to and add the next point on the straight path.
                    if (extraPathLength > 0) {
                        yPoint = roller.endY + normalizedY * (deltaDist - extraPathLength);
                        zPoint = roller.endZ + normalizedZ * (deltaDist - extraPathLength);
                        straightPathLength -= (deltaDist - extraPathLength);
                        extraPathLength = 0;
                    } else {
                        yPoint += normalizedY * deltaDist;
                        zPoint += normalizedZ * deltaDist;
                        straightPathLength -= deltaDist;
                    }
                    points.add(new Double[]{yPoint, zPoint, currentAngle + 180});
                }
                leftoverPathLength = straightPathLength;
            }
        }
        return points;
    }

    /**
     * Custom URL downloader class to prevent blocking of the main thread when downloading textures
     * and to give more time for the downloader to run.
     *
     * @author don_bruce
     */
    private static class ConnectorThread extends Thread {
        private final String urlString;
        private final RenderableModelObject objectOn;

        public ConnectorThread(String urlString, RenderableModelObject objectOn) {
            this.urlString = urlString;
            this.objectOn = objectOn;
        }

        @Override
        public void run() {
            //Parse the texture out into an InputStream, if possible, and bind it.
            //FAR less jank than using MC's resource system.
            //We try a few times here since sources can do dumb things.
            int tryCount = 0;
            String errorString = null;
            do {
                try {
                    URL urlObject = new URL(urlString);
                    HttpURLConnection connection = (HttpURLConnection) urlObject.openConnection();
                    try {
                        connection.connect();
                        String contentType = connection.getContentType();
                        String[] typeParams = contentType.split("/");
                        if (typeParams[0].equals("text")) {
                            errorString = "ERROR: Found only text at the URL.  This is not a direct image link, or you don't have permission to view this image (hosted behind a login).";
                        } else {
                            Iterator<ImageReader> iterator = ImageIO.getImageReadersByFormatName(typeParams[1]);
                            if (iterator.hasNext()) {
                                ImageReader reader = iterator.next();
                                if (typeParams[1].equals("gif")) {
                                    ImageInputStream stream = ImageIO.createImageInputStream(connection.getInputStream());
                                    reader.setInput(stream);
                                    ParsedGIF gif = GIFParser.parseGIF(reader);
                                    if (gif != null) {
                                        if (InterfaceManager.renderingInterface.bindURLGIF(urlString, gif)) {
                                            objectOn.downloadedTextures.add(urlString);
                                            objectOn.downloadingTextures.remove(urlString);
                                            return;
                                        } else {
                                            errorString = "ERROR: Could not parse GIF due to an internal MC-system interface error.  Contact the mod author!";
                                        }
                                    } else {
                                        errorString = "ERROR: Could not parse GIF due to no frames being present.  Is this a real direct link or a fake one?";
                                    }
                                } else {
                                    if (InterfaceManager.renderingInterface.bindURLTexture(urlString, connection.getInputStream())) {
                                        objectOn.downloadedTextures.add(urlString);
                                        objectOn.downloadingTextures.remove(urlString);
                                        return;
                                    } else {
                                        errorString = "ERROR: Got a correct image type, but was missing data for the image?  Likely partial data sent by the server source, try again later.";
                                    }
                                }
                            } else {
                                errorString = "ERROR: Invalid content type found.  Found:" + contentType + ", but the only valid types are: ";
                                for (String imageSuffix : ImageIO.getReaderFileSuffixes()) {
                                    errorString += ("image/" + imageSuffix + ", ");
                                }
                            }
                        }
                    } catch (Exception e) {
                        errorString = "ERROR: Could not parse images.  Error was: " + e.getMessage();
                    }
                } catch (Exception e) {
                    errorString = "ERROR: Could not open URL for processing.  Error was: " + e.getMessage();
                }
            } while (++tryCount < 10);

            //Set missing texture if we failed to get anything.
            InterfaceManager.renderingInterface.bindURLTexture(urlString, null);
            erroredTextures.put(urlString, errorString);
            objectOn.downloadingTextures.remove(urlString);
            objectOn.downloadedTextures.add(urlString);
        }
    }
}
