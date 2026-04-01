package dev.duma.android.hal.plugins.sunmi.rfid.descriptor

import dev.duma.android.hal.contract.MethodDescriptor

internal object RfidMethodDescriptors {

    fun inventoryMethods() = listOf(
        MethodDescriptor(
            "sunmi.rfid.getScanModel",
            "Gets RFID module type.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"available":true,"modelId":101,"model":"UHF_R2000"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.inventory",
            "6C inventory — buffer mode. Tags via tagFound events, summary via operationSuccess.",
            "sunmi.rfid",
            exampleParameters = """{"btRepeat":255}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.realTimeInventory",
            "6C inventory — real-time. Emits tagFound per tag.",
            "sunmi.rfid",
            exampleParameters = """{"btRepeat":255}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.customizedSessionTargetInventory",
            "6C inventory — session/target (async, recommended).",
            "sunmi.rfid",
            exampleParameters = """{"btSession":1,"btTarget":0,"btSL":0,"btPhase":0,"btPowerSave":0,"btRepeat":255}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.fastSwitchAntInventory",
            "6C inventory — fast antenna switch.",
            "sunmi.rfid",
            exampleParameters = """{"btA":0,"btStayA":1,"btB":1,"btStayB":1,"btC":255,"btStayC":1,"btD":255,"btStayD":1,"btInterval":10,"btRepeat":255}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.realTimeInventoryWithTid",
            "6C inventory with TID reading.",
            "sunmi.rfid",
            exampleParameters = """{"scanTime":0,"btTidLen":6,"btTarget":0,"btScan":0,"btAryEpc":""}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.iso180006BInventory",
            "6B inventory. Emits tagFound per tag.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getInventoryBuffer",
            "Get buffered 6C tags. Tags via tagFound events.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getAndResetInventoryBuffer",
            "Get buffered 6C tags and clear buffer. Tags via tagFound events.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"status":"started"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getInventoryBufferTagCount",
            "Get number of buffered 6C tags.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"count":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.resetInventoryBuffer",
            "Clear 6C tag buffer.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{}"""
        ),
    )

    fun tag6CMethods() = listOf(
        MethodDescriptor(
            "sunmi.rfid.readTag",
            "Read 6C tag data. Call setAccessEpcMatch first.",
            "sunmi.rfid",
            exampleParameters = """{"btMemBank":2,"btWordAdd":0,"btWordCnt":6,"btAryPassWord":"00000000"}""",
            exampleOutput = """{"data":"AABBCCDD"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.writeTag",
            "Write 6C tag data. Call setAccessEpcMatch first.",
            "sunmi.rfid",
            exampleParameters = """{"btAryPassWord":"00000000","btMemBank":1,"btWordAdd":2,"btWordCnt":6,"btAryData":"AABBCCDD..."}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.lockTag",
            "Lock 6C tag memory bank. lockType: 0=open, 1=lock, 2=perm.open, 3=perm.locked.",
            "sunmi.rfid",
            exampleParameters = """{"btAryPassWord":"00000000","btMemBank":1,"btLockType":1}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.killTag",
            "Kill 6C tag permanently.",
            "sunmi.rfid",
            exampleParameters = """{"btAryPassWord":"00000000"}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setAccessEpcMatch",
            "Set EPC filter for tag operations.",
            "sunmi.rfid",
            exampleParameters = """{"btAryEpc":"AABBCCDD..."}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.cancelAccessEpcMatch",
            "Clear EPC filter.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getAccessEpcMatch",
            "Get current EPC filter.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"epcFilter":"AABBCCDD"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setImpinjFastTid",
            "Enable FastTID for Impinj Monza tags.",
            "sunmi.rfid",
            exampleParameters = """{"blnOpen":true,"blnSave":false}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getImpinjFastTid",
            "Get FastTID status.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"fastTidStatus":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setImpinjSaveTagFocus",
            "Enable Impinj tag focus and save.",
            "sunmi.rfid",
            exampleParameters = """{"blnOpen":true}""",
            exampleOutput = """{}"""
        ),
    )

    fun tag6BMethods() = listOf(
        MethodDescriptor(
            "sunmi.rfid.iso180006BReadTag",
            "Read 6B tag.",
            "sunmi.rfid",
            exampleParameters = """{"btAryUID":"AABBCCDD11223344","btWordAdd":0,"btWordCnt":4}""",
            exampleOutput = """{"data":"AABBCCDD"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.iso180006BWriteTag",
            "Write 6B tag.",
            "sunmi.rfid",
            exampleParameters = """{"btAryUID":"AABBCCDD11223344","btWordAdd":0,"btWordCnt":2,"btAryBuffer":"AABB"}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.iso180006BLockTag",
            "Lock 6B tag byte.",
            "sunmi.rfid",
            exampleParameters = """{"btAryUID":"AABBCCDD11223344","btWordAdd":10}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.iso180006BQueryLockTag",
            "Query 6B tag lock status.",
            "sunmi.rfid",
            exampleParameters = """{"btAryUID":"AABBCCDD11223344","btWordAdd":10}""",
            exampleOutput = """{"lockStatus":0}"""
        ),
    )

    fun readerConfigMethods() = listOf(
        MethodDescriptor(
            "sunmi.rfid.setWorkAntenna",
            "Set active antenna.",
            "sunmi.rfid",
            exampleParameters = """{"btAntId":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getWorkAntenna",
            "Get active antenna.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"workAntenna":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setOutputAllPower",
            "Set output power for all antennas, in dBm.",
            "sunmi.rfid",
            exampleParameters = """{"btOutputPower":26}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setOutputPower",
            "Set per-antenna output power.",
            "sunmi.rfid",
            exampleParameters = """{"btPower1":26,"btPower2":26,"btPower3":26,"btPower4":26}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getOutputPower",
            "Get output power.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"outputPower":[26,26,26,26]}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setTemporaryOutputPower",
            "Set non-persistent output power.",
            "sunmi.rfid",
            exampleParameters = """{"btOutputPower":26}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setFrequencyRegion",
            "Set frequency region.",
            "sunmi.rfid",
            exampleParameters = """{"btRegion":1,"btStart":0,"btEnd":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setUserDefineFrequency",
            "Set user-defined frequency.",
            "sunmi.rfid",
            exampleParameters = """{"btQuantity":1,"btFreqInterval":1,"nStartFreq":920125}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getFrequencyRegion",
            "Get frequency region.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"frequencyRegion":1,"frequencyStart":0,"frequencyEnd":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setFixedFrequency",
            "Set fixed frequency mode.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setBeeperMode",
            "Set beeper mode. 0=off, 1=on.",
            "sunmi.rfid",
            exampleParameters = """{"btMode":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getBeeperMode",
            "Get beeper mode.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"beepMode":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setRfLinkProfile",
            "Set RF link profile.",
            "sunmi.rfid",
            exampleParameters = """{"btProfile":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getRfLinkProfile",
            "Get RF link profile.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"rfLinkProfile":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getRfPortReturnLoss",
            "Get RF port return loss.",
            "sunmi.rfid",
            exampleParameters = """{"btFreq":1}""",
            exampleOutput = """{"rfPortReturnLoss":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setAntConnectionDetector",
            "Set antenna connection detector.",
            "sunmi.rfid",
            exampleParameters = """{"btPower":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getAntConnectionDetector",
            "Get antenna connection detector.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"antConnectionDetector":0}"""
        ),
    )

    fun readerInfoMethods() = listOf(
        MethodDescriptor(
            "sunmi.rfid.setReaderIdentifier",
            "Set reader identifier.",
            "sunmi.rfid",
            exampleParameters = """{"btAryIdentifier":"AABB..."}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getReaderIdentifier",
            "Get reader identifier.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"readerIdentifier":"AABB0011"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getReaderSN",
            "Get reader serial number.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"sn":"SN12345678"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getReaderCustomSN",
            "Get reader custom serial number.",
            "sunmi.rfid",
            exampleParameters = """{"btMode":0}""",
            exampleOutput = """{"customSn":"CSN12345678"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getReaderVersion",
            "Get reader hardware version.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"version":"1.0.0"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getFirmwareVersion",
            "Get firmware version.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"firmwareVersion":"2.0.0"}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getReaderTemperature",
            "Get reader temperature.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"temperature":25,"plusMinus":"+"}"""
        ),
    )

    fun batteryGpioMethods() = listOf(
        MethodDescriptor(
            "sunmi.rfid.getBatteryRemainingPercent",
            "Get battery remaining percentage.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"batteryRemainingPercent":85}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getBatteryVoltage",
            "Get battery voltage.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"batteryVoltage":3700}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getBatteryChargeState",
            "Get battery charge state.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"batteryCharging":false}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getBatteryChargeNumTimes",
            "Get battery charge cycle count.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"batteryChargingNumTimes":100}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.readGpioValue",
            "Read GPIO values.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"gpio1":0,"gpio2":0}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.writeGpioValue",
            "Write GPIO value.",
            "sunmi.rfid",
            exampleParameters = """{"btPort":1,"btValue":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setPowerDown",
            "Set power-down mode.",
            "sunmi.rfid",
            exampleParameters = """{"nIdleTime":0,"btUnit":0}""",
            exampleOutput = """{}"""
        ),
    )

    fun systemMethods() = listOf(
        MethodDescriptor(
            "sunmi.rfid.resetReader",
            "Reset reader.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.reset",
            "Reset RFID module.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setReaderAddress",
            "Set reader address.",
            "sunmi.rfid",
            exampleParameters = """{"btAddress":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.setTagMask",
            "Set tag mask filter.",
            "sunmi.rfid",
            exampleParameters = """{"btMaskId":0,"btTarget":0,"btAction":0,"btMembank":1,"btStartAdd":0,"btMaskLen":0,"btAryMaskData":"AABB..."}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.clearTagMask",
            "Clear tag mask.",
            "sunmi.rfid",
            exampleParameters = """{"btMaskId":0}""",
            exampleOutput = """{}"""
        ),
        MethodDescriptor(
            "sunmi.rfid.getTagMask",
            "Get tag mask configuration.",
            "sunmi.rfid",
            exampleParameters = """{}""",
            exampleOutput = """{"id":0,"target":0,"action":0,"membank":1,"startAddress":0,"length":0,"value":""}"""
        ),
    )
}
