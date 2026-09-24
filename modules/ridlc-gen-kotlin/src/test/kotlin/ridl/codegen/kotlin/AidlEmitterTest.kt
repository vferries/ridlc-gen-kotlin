package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.AidlEmitter
import ridl.codegen.v1.ModelOuterClass.CommandShape
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.InteractionSlot
import ridl.codegen.v1.ModelOuterClass.Interface
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Spellings

/** Where the AIDL puts its codes, and what it refuses: what the corpus does not reach. */
class AidlEmitterTest {
    private val options = Options("kt.demo", WireEncoding.FlatBuffers)

    private fun spelled(name: String) = Spellings.newBuilder().setDeclared(name).setCamel(name.replaceFirstChar(Char::uppercaseChar)).build()

    private fun model(ordinal: Int): Model {
        val go = Interaction.newBuilder().setName(spelled("go")).setCommand(CommandShape.getDefaultInstance())
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(InteractionSlot.newBuilder().setOrdinal(ordinal).setInteraction(go))
        return Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).addInterfaces(iface).build()
    }

    @Test
    fun `the control plane is on the four highest codes aidl admits`() {
        assertEquals(listOf(16_777_111, 16_777_112, 16_777_113, 16_777_114),
            listOf(AidlEmitter.ATTACH, AidlEmitter.SUBSCRIBE, AidlEmitter.UNSUBSCRIBE, AidlEmitter.READ))
    }

    @Test
    fun `a call on its ordinal, the listener and the three parcelables`() {
        val (files, warnings) = AidlEmitter(model(1), options).emit()
        assertEquals(emptyList<String>(), warnings)
        assertEquals(
            listOf("aidl/kt/demo/IDrive.aidl", "aidl/kt/demo/IDriveListener.aidl", "aidl/ridl/rt/Frame.aidl", "aidl/ridl/rt/Outcome.aidl", "aidl/ridl/rt/CatalogRef.aidl"),
            files.map { it.path },
        )
        assertTrue("oneway void go(in Frame args) = 1;" in files[0].text, files[0].text)
    }

    @Test
    fun `a call whose ordinal reaches the control plane is refused with a warning`() {
        val (files, warnings) = AidlEmitter(model(AidlEmitter.ATTACH), options).emit()
        assertEquals(emptyList<AidlEmitter.File>(), files)
        assertTrue("`kt.demo.Drive`" in warnings.single() && "control plane" in warnings[0], warnings[0])
    }
}
