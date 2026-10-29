package com.atakmap.android.lingualinkledger.plugin;

import com.atakmap.android.location.framework.Location;
import com.atakmap.android.location.framework.LocationDerivation;
import com.atakmap.android.location.framework.LocationManager;
import com.atakmap.android.location.framework.LocationProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * Reads the phone's position as ATAK knows it, never the map's centre
 * (PL-18; V1.2, D43), into a PositionPolicy.Reading:
 *
 *  - ATAK 5.8's location framework: the preferred location provider's last
 *    reported location, with its point, fix time and horizontal source;
 *  - cross-checked with the self marker's GeoPointMetaData source.
 *
 * When the provider has no fix but the self marker's position was set by
 * hand in ATAK ("set my location"), that hand-set point is used, as a
 * manual location. Which values ATAK reports in each case is logged, to
 * be confirmed on the S21+ (Day 1, step 5).
 */
final class AtakPosition {

    private static final String TAG = "LinguaLinkLedger.Position";

    private AtakPosition() {
    }

    static PositionPolicy.Reading read() {
        long now = System.currentTimeMillis();

        String providerId = null, providerSource = null;
        GeoPoint providerPoint = null;
        long fixTime = 0;
        LocationProvider provider = LocationManager.getInstance().getPreferredLocationProvider();
        if (provider != null) {
            providerId = provider.getUniqueIdentifier();
            Location loc = provider.getLastReportedLocation();
            if (loc != null && loc.isValid()) {
                providerPoint = loc.getPoint();
                fixTime = loc.getLocationDerivedTime();
                LocationDerivation d = loc.getDerivation();
                providerSource = d == null ? null : d.getHorizontalSource();
            }
        }

        String markerSource = null;
        GeoPoint markerPoint = null;
        MapView mapView = MapView.getMapView();
        Marker self = mapView == null ? null : mapView.getSelfMarker();
        if (self != null) {
            GeoPointMetaData md = self.getGeoPointMetaData();
            if (md != null) {
                markerSource = md.getGeopointSource();
                markerPoint = md.get();
            }
        }

        PositionPolicy.Source source = providerPoint == null
                ? PositionPolicy.classify(null, markerSource)
                : PositionPolicy.classify(providerSource, markerSource);

        // the point to use: the provider's fix, or the hand-set self marker
        GeoPoint point = providerPoint;
        if (source == PositionPolicy.Source.MANUAL && !"USER".equals(providerSource))
            point = markerPoint;
        if (point == null)
            source = PositionPolicy.Source.NONE;

        // PL-40: the sources, times and accuracy, never the coordinates. ATAK
        // copies recent log lines into its crash reports on shared storage.
        String providerFix = presence(providerPoint), markerFix = presence(markerPoint);
        Log.d(TAG, "provider=" + providerId + " providerSource=" + providerSource
                + " providerFix=" + providerFix
                + " fixTime=" + fixTime + " now=" + now + " fixMinusNowMs=" + (fixTime > 0 ? fixTime - now : 0)
                + " selfMarkerSource=" + markerSource + " selfMarker=" + markerFix
                + " -> " + source);

        double lat = point == null ? Double.NaN : point.getLatitude();
        double lon = point == null ? Double.NaN : point.getLongitude();
        double ce = point == null ? Double.NaN : point.getCE();
        return new PositionPolicy.Reading(source, lat, lon, ce,
                source == PositionPolicy.Source.MANUAL && point == markerPoint ? 0 : fixTime,
                providerSource, markerSource);
    }

    /** Whether there is a point, and its accuracy; no coordinates (PL-40). */
    private static String presence(GeoPoint p) {
        return p == null ? "none" : "present ce=" + p.getCE();
    }
}
