package imehc.recoow.core.database

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalClientSourceTest {
    @Test fun sourceDescribesTheEditingInstallationAndForeignRecordsStayUnknown() {
        val source = LocalClientSource("android-installation", "Phone", "0.7.1", "16")
        val frozen = Json.parseToJsonElement(requireNotNull(source.origin("android-installation"))).jsonObject
        assertEquals("android", frozen.getValue("client").jsonPrimitive.content)
        assertEquals("0.7.1", frozen.getValue("appVersion").jsonPrimitive.content)
        assertNull(source.origin("restored-ios-installation"))
        assertEquals("android-installation", source.profile().getValue("localDeviceId").jsonPrimitive.content)
        assertFalse("deviceId" in source.profile())
    }
}
