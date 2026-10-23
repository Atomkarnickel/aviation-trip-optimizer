package com.atomkarnickel.overhead

import com.atomkarnickel.overhead.domain.AircraftKind
import com.atomkarnickel.overhead.domain.AltitudeUnit
import com.atomkarnickel.overhead.domain.Format
import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.LatLon
import com.atomkarnickel.overhead.domain.RadarProjection
import com.atomkarnickel.overhead.domain.ReferenceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun distanceMatchesKnownRoute() {
        // Zürich → Frankfurt ≈ 287 km (Großkreis)
        assertEquals(287.0, Geo.distanceKm(47.46, 8.55, 50.03, 8.56), 3.0)
        assertEquals(0.0, Geo.distanceKm(50.0, 8.0, 50.0, 8.0), 1e-9)
    }

    @Test
    fun bearingPointsToCardinalDirections() {
        assertEquals(0.0, Geo.bearingDeg(50.0, 8.0, 51.0, 8.0), 0.5)
        assertEquals(180.0, Geo.bearingDeg(50.0, 8.0, 49.0, 8.0), 0.5)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.5)
        assertEquals(270.0, Geo.bearingDeg(0.0, 1.0, 0.0, 0.0), 0.5)
    }

    @Test
    fun elevationIs90DegreesStraightOverheadAndAccountsForCurvature() {
        assertEquals(90.0, Geo.elevationDeg(0.0, 10_000.0), 1e-6)
        assertEquals(45.0, Geo.elevationDeg(10.0, 10_000.0), 0.1)
        // 10 km Höhe in 400 km Entfernung liegt wegen der Erdkrümmung unter dem Horizont.
        assertTrue(Geo.elevationDeg(400.0, 10_000.0) < 0)
    }

    @Test
    fun compassAndArrowUseEightSectors() {
        assertEquals("N", Geo.compass(0.0))
        assertEquals("N", Geo.compass(359.0))
        assertEquals("NO", Geo.compass(44.0))
        assertEquals("O", Geo.compass(91.0))
        assertEquals("SW", Geo.compass(225.0))
        assertEquals("NW", Geo.compass(-45.0))
        assertEquals("↑", Geo.arrow(10.0))
        assertEquals("↓", Geo.arrow(180.0))
    }

    @Test
    fun destinationIsInverseOfDistanceAndBearing() {
        val p = Geo.destination(50.0, 8.0, 63.0, 42.0)
        assertEquals(42.0, Geo.distanceKm(50.0, 8.0, p.lat, p.lon), 0.01)
        assertEquals(63.0, Geo.bearingDeg(50.0, 8.0, p.lat, p.lon), 0.1)
    }

    @Test
    fun routePlausibility() {
        val ayt = LatLon(36.90, 30.80)
        val lgw = LatLon(51.15, -0.19)
        // Zwischen Antalya und London über Süddeutschland: plausibel
        assertEquals(true, Geo.routePlausible(listOf(ayt, lgw), LatLon(47.6, 8.4)))
        // Über Skandinavien: nicht plausibel
        assertEquals(false, Geo.routePlausible(listOf(ayt, lgw), LatLon(60.0, 10.0)))
        // Großkreis London → Singapur führt weit nach Norden: Nordrussland ist plausibel, New York nicht
        val lhr = LatLon(51.47, -0.46)
        val sin = LatLon(1.36, 103.99)
        assertEquals(true, Geo.routePlausible(listOf(lhr, sin), LatLon(55.0, 40.0)))
        assertEquals(false, Geo.routePlausible(listOf(lhr, sin), LatLon(40.64, -73.78)))
        // Fehlende Koordinaten: nicht prüfbar
        assertNull(Geo.routePlausible(listOf(ayt, null), LatLon(47.6, 8.4)))
        // Mehrere Teilstrecken: ein passendes Teilstück reicht
        assertEquals(true, Geo.routePlausible(listOf(LatLon(60.0, 10.0), ayt, lgw), LatLon(47.6, 8.4)))
    }
}

class FormatTest {
    @Test
    fun altitudeInMetersAndFeet() {
        assertEquals("10 670 m", Format.altitude(35000, false, AltitudeUnit.METERS))
        assertEquals("35 000 ft", Format.altitude(35000, false, AltitudeUnit.FEET))
        assertEquals("am Boden", Format.altitude(null, true, AltitudeUnit.METERS))
        assertEquals("Höhe ?", Format.altitude(null, false, AltitudeUnit.FEET))
    }

    @Test
    fun distanceSpeedAndAge() {
        assertEquals("4,2 km", Format.distance(4.24))
        assertEquals("23 km", Format.distance(23.4))
        assertEquals("926 km/h", Format.speed(500.0))
        assertEquals("–", Format.speed(null))
        assertEquals("gerade eben", Format.age(0, 30_000))
        assertEquals("vor 3 min", Format.age(0, 200_000))
        assertEquals("vor 2 h", Format.age(0, 7_300_000))
    }

    @Test
    fun climbAndElevation() {
        assertEquals("↗", Format.climbArrow(1500))
        assertEquals("↘", Format.climbArrow(-800))
        assertEquals("→", Format.climbArrow(0))
        assertEquals("", Format.climbArrow(null))
        assertEquals("Reiseflug", Format.verticalRate(64))
        assertEquals("sinkt 2,9 m/s", Format.verticalRate(-576))
        assertEquals("34° über dem Horizont", Format.elevation(34.4))
        assertEquals("unter dem Horizont", Format.elevation(-1.0))
    }

    @Test
    fun thousandsSeparator() {
        assertEquals("1 234 567", Format.thousands(1234567))
        assertEquals("999", Format.thousands(999))
        assertEquals("−1 000", Format.thousands(-1000))
    }
}

class ReferenceDataTest {
    @Test
    fun typeNamesFromTableThenDescriptionThenCode() {
        assertEquals("Airbus A321neo", reference.typeName("A21N"))
        assertEquals("Boeing 787-9", reference.typeName("b789"))
        assertEquals("Boeing E-3 Sentry", reference.typeName("E3TF", "Boeing E-3G Sentry"))
        assertEquals("Boeing 787-9 Dreamliner", reference.typeName("XXXX".takeIf { false }, "BOEING 787-9 Dreamliner"))
        assertEquals("ZZ99", reference.typeName("ZZ99"))
        assertNull(reference.typeName(null, null))
    }

    @Test
    fun airlineOnlyForAirlineCallsigns() {
        assertEquals("SWISS", reference.airlineName("SWR38"))
        assertEquals("easyJet", reference.airlineName("EZY73HP"))
        assertEquals("British Airways", reference.airlineName("BAW15"))
        assertNull(reference.airlineName("HBJXA")) // Registrierung als Rufzeichen
        assertNull(reference.airlineName("SHUCK81")) // militärisches Rufzeichen
        assertNull(reference.airlineName(null))
    }

    @Test
    fun prettyDescription() {
        assertEquals("Airbus A-321neo", ReferenceData.prettyDescription("AIRBUS A-321neo"))
        assertNull(ReferenceData.prettyDescription("  "))
    }

    @Test
    fun kindFromEmitterCategory() {
        assertEquals(AircraftKind.HEAVY, AircraftKind.of("A5"))
        assertEquals(AircraftKind.HELICOPTER, AircraftKind.of("a7"))
        assertEquals(AircraftKind.GROUND, AircraftKind.of("C2"))
        assertEquals(AircraftKind.UNKNOWN, AircraftKind.of(null))
        assertFalse(AircraftKind.of("A1") == AircraftKind.AIRLINER)
    }
}

class RadarProjectionTest {
    @Test
    fun northIsUpAndEdgeIsRadius() {
        val center = RadarProjection.project(0.0, 0.0, 25.0, 200f)
        assertEquals(100f, center.x, 0.01f)
        assertEquals(100f, center.y, 0.01f)
        val north = RadarProjection.project(0.0, 25.0, 25.0, 200f, paddingPx = 10f)
        assertEquals(100f, north.x, 0.01f)
        assertEquals(10f, north.y, 0.01f)
        val east = RadarProjection.project(90.0, 12.5, 25.0, 200f)
        assertEquals(150f, east.x, 0.01f)
        assertEquals(100f, east.y, 0.01f)
        // Außerhalb des Radius → auf den Rand geklemmt
        val far = RadarProjection.project(180.0, 99.0, 25.0, 200f)
        assertEquals(200f, far.y, 0.01f)
    }
}
