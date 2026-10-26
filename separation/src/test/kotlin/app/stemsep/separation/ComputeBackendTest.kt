package app.stemsep.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeBackendTest {
    @Test
    fun recognizesQualcommManufacturer() {
        assertTrue(
            ComputeCapabilityDetector.isSnapdragonSoc(
                manufacturer = "Qualcomm",
                hardware = "qcom",
                board = "kalama",
                socModel = "SM8650",
            ),
        )
    }

    @Test
    fun recognizesSnapdragonModelWithoutManufacturer() {
        assertTrue(
            ComputeCapabilityDetector.isSnapdragonSoc(
                manufacturer = "",
                hardware = "",
                board = "",
                socModel = "SM8550",
            ),
        )
    }

    @Test
    fun rejectsOtherSoCs() {
        assertFalse(
            ComputeCapabilityDetector.isSnapdragonSoc(
                manufacturer = "MediaTek",
                hardware = "mt6989",
                board = "mt6989",
                socModel = "MT6989",
            ),
        )
        assertFalse(
            ComputeCapabilityDetector.isSnapdragonSoc(
                manufacturer = "Google",
                hardware = "tensor",
                board = "zuma",
                socModel = "Tensor G3",
            ),
        )
        assertFalse(
            ComputeCapabilityDetector.isSnapdragonSoc(
                manufacturer = "Samsung",
                hardware = "exynos",
                board = "exynos",
                socModel = "Exynos 2400",
            )
        )
    }

    @Test
    fun parsesBackendIdsAndFallsBackToCpu() {
        assertEquals(ComputeBackend.CPU, ComputeBackend.fromId("cpu"))
        assertEquals(ComputeBackend.SNAPDRAGON_NNAPI, ComputeBackend.fromId("nnapi"))
        assertEquals(ComputeBackend.CPU, ComputeBackend.fromId(null))
        assertEquals(ComputeBackend.CPU, ComputeBackend.fromId("unknown"))
    }
}
