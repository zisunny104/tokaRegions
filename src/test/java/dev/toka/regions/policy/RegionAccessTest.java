package dev.toka.regions.policy;

import dev.toka.regions.model.Region.Protection;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegionAccessTest {
    @Test public void openRegionsForbidNobody() {
        assertFalse(RegionAccess.denies(Protection.OPEN, false, null, null));
    }

    @Test public void lockedRegionsAreForAdministratorsEvenInsideAClaim() {
        assertTrue(RegionAccess.denies(Protection.LOCK, false, true, true));
        assertTrue(RegionAccess.denies(Protection.LOCK, true, false, false));
    }

    @Test public void protectedRegionsAllowAdministratorsAndLandOwners() {
        assertFalse(RegionAccess.denies(Protection.PROTECT, true, false, false));
        assertFalse(RegionAccess.denies(Protection.PROTECT, false, false, true));
        assertTrue(RegionAccess.denies(Protection.PROTECT, false, false, false));
    }

    @Test public void insideAClaimTheRegionStepsAside() {
        assertFalse(RegionAccess.denies(Protection.PROTECT, false, true, false));
    }

    @Test public void whatCannotBeKnownNeverCountsInThePlayersFavour() {
        assertTrue(RegionAccess.denies(Protection.PROTECT, false, null, null));
        assertTrue(RegionAccess.denies(Protection.PROTECT, false, null, false));
    }
    @org.junit.Test public void aLockedRegionForbidsEveryoneIncludingAdministrators() {
        assertTrue(RegionAccess.denies(Protection.LOCK, true, false, false));
        assertTrue(RegionAccess.denies(Protection.LOCK, false, true, true));
        assertFalse(RegionAccess.denies(Protection.PROTECT, true, false, false));
    }
}
