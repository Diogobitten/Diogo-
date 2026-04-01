package com.nuvio.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = "com.nuvio.tv.debug",
            includeInStartupProfile = true
        ) {
            // 1. Cold start — captures startup, Hilt init, Compose first frame, Coil init
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("com.nuvio.tv.debug")), 8_000)

            // 2. Wait for home screen content to load (catalogs, images)
            Thread.sleep(5_000)

            // 3. Scroll down through home screen rows — captures LazyColumn/LazyRow
            //    composition, image decoding, catalog row rendering
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                repeat(3) {
                    scrollable.scroll(Direction.DOWN, 0.8f)
                    Thread.sleep(1_000)
                }
                // Scroll back up to top
                repeat(3) {
                    scrollable.scroll(Direction.UP, 0.8f)
                    Thread.sleep(500)
                }
            }

            // 4. Navigate right through a catalog row — captures horizontal scrolling,
            //    poster card composition, focus animations
            repeat(5) {
                device.pressDPadRight()
                Thread.sleep(300)
            }
            Thread.sleep(1_000)

            // 5. Select an item to navigate to detail screen — captures navigation,
            //    detail screen composition, TMDB enrichment, backdrop loading
            device.pressDPadCenter()
            Thread.sleep(4_000)

            // 6. Scroll down on detail screen — captures episode list, more-like-this,
            //    reviews rendering
            repeat(3) {
                device.pressDPadDown()
                Thread.sleep(500)
            }

            // 7. Go back to home — captures back navigation, state restoration
            device.pressBack()
            Thread.sleep(2_000)

            // 8. Navigate to search screen via sidebar — captures sidebar rendering,
            //    search screen composition
            device.pressDPadLeft()
            Thread.sleep(500)
            device.pressDPadDown()
            Thread.sleep(300)
            device.pressDPadDown()
            Thread.sleep(300)
            device.pressDPadCenter()
            Thread.sleep(2_000)

            // 9. Go back to home
            device.pressBack()
            Thread.sleep(1_000)
        }
    }
}
