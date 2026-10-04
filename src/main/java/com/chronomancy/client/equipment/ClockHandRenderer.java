package com.chronomancy.client.equipment;

import com.chronomancy.item.ClockHandItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public final class ClockHandRenderer extends GeoItemRenderer<ClockHandItem> {
    public ClockHandRenderer() { super(new ClockworkGeoModel<>("clock_hand")); }
}
