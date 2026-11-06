package com.iqonli.photo2fresh

import androidx.test.platform.app.InstrumentationRegistry
import com.iqonli.photo2fresh.regression.MeatDeploymentSelfCheck
import org.junit.Assert.assertEquals
import org.junit.Test

class DeploymentIntegrationTest {
    @Test fun productionPipelineMatchesAllTwelveDeploymentFixtures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(12, MeatDeploymentSelfCheck.run(context).size)
    }
}
