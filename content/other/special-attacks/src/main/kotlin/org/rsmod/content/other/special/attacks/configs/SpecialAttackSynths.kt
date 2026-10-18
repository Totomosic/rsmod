package org.rsmod.content.other.special.attacks.configs

import org.rsmod.game.type.synth.SynthType

internal object SpecialAttackSynths {
    // Cache sounds absent from the synth symbol table.
    // https://oldschool.runescape.wiki/w/File:Dragon_claws_special_attack_1.wav
    // https://oldschool.runescape.wiki/w/File:Dragon_claws_special_attack_2.wav
    // https://oldschool.runescape.wiki/w/File:Dragon_claws_special_attack_3.wav
    val clawsFirst = SynthType(4138, "dragonclaws_special_1")
    val clawsSecond = SynthType(4140, "dragonclaws_special_2")
    val clawsLast = SynthType(4141, "dragonclaws_special_3")

    // The bulwark's configured bash sound (also used by the barrelchest anchor).
    val bulwark = SynthType(3454, "brain_anchor_attack")
}
