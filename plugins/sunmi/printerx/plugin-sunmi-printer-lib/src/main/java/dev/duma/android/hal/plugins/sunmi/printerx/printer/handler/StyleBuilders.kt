package dev.duma.android.hal.plugins.sunmi.printerx.printer.handler

import com.sunmi.printerx.enums.Align
import com.sunmi.printerx.enums.ErrorLevel
import com.sunmi.printerx.enums.HumanReadable
import com.sunmi.printerx.enums.ImageAlgorithm
import com.sunmi.printerx.enums.RenderColor
import com.sunmi.printerx.enums.Rotate
import com.sunmi.printerx.enums.Symbology
import com.sunmi.printerx.style.BaseStyle
import com.sunmi.printerx.style.BarcodeStyle
import com.sunmi.printerx.style.BitmapStyle
import com.sunmi.printerx.style.LabelStyle
import com.sunmi.printerx.style.QrStyle
import com.sunmi.printerx.style.TextStyle
import org.json.JSONObject

internal fun buildBaseStyle(json: JSONObject): BaseStyle {
    val style = BaseStyle.getStyle()
    if (json.has("align")) style.setAlign(Align.valueOf(json.getString("align")))
    if (json.has("width")) style.setWidth(json.getInt("width"))
    if (json.has("height")) style.setHeight(json.getInt("height"))
    if (json.has("posX")) style.setPosX(json.getInt("posX"))
    if (json.has("posY")) style.setPosY(json.getInt("posY"))
    if (json.has("renderColor")) style.setRenderColor(RenderColor.valueOf(json.getString("renderColor")))
    return style
}

internal fun buildLabelStyle(json: JSONObject): LabelStyle {
    val style = LabelStyle.getStyle()
    if (json.has("align")) style.setAlign(Align.valueOf(json.getString("align")))
    if (json.has("width")) style.setWidth(json.getInt("width"))
    if (json.has("height")) style.setHeight(json.getInt("height"))
    if (json.has("posX")) style.setPosX(json.getInt("posX"))
    if (json.has("posY")) style.setPosY(json.getInt("posY"))
    if (json.has("renderColor")) style.setRenderColor(RenderColor.valueOf(json.getString("renderColor")))
    if (json.has("reverse")) style.enableReverse(json.getBoolean("reverse"))
    if (json.has("mirror")) style.enableMirror(json.getBoolean("mirror"))
    if (json.has("back")) style.enableBack(json.getBoolean("back"))
    if (json.has("tear")) style.enableTear(json.getBoolean("tear"))
    return style
}

internal fun buildTextStyle(json: JSONObject): TextStyle {
    val style = TextStyle.getStyle()
    if (json.has("textSize")) style.setTextSize(json.getInt("textSize"))
    if (json.has("textWidthRatio")) style.setTextWidthRatio(json.getInt("textWidthRatio"))
    if (json.has("textHeightRatio")) style.setTextHeightRatio(json.getInt("textHeightRatio"))
    if (json.has("textSpace")) style.setTextSpace(json.getInt("textSpace"))
    if (json.has("bold")) style.enableBold(json.getBoolean("bold"))
    if (json.has("underline")) style.enableUnderline(json.getBoolean("underline"))
    if (json.has("strikethrough")) style.enableStrikethrough(json.getBoolean("strikethrough"))
    if (json.has("italics")) style.enableItalics(json.getBoolean("italics"))
    if (json.has("invert")) style.enableInvert(json.getBoolean("invert"))
    if (json.has("antiColor")) style.enableAntiColor(json.getBoolean("antiColor"))
    if (json.has("font")) style.setFont(json.getString("font"))
    if (json.has("align")) style.setAlign(Align.valueOf(json.getString("align")))
    if (json.has("posX")) style.setPosX(json.getInt("posX"))
    if (json.has("posY")) style.setPosY(json.getInt("posY"))
    if (json.has("width")) style.setWidth(json.getInt("width"))
    if (json.has("height")) style.setHeight(json.getInt("height"))
    if (json.has("rotate")) style.setRotate(Rotate.valueOf(json.getString("rotate")))
    if (json.has("renderColor")) style.setRenderColor(RenderColor.valueOf(json.getString("renderColor")))
    return style
}

internal fun buildBarcodeStyle(json: JSONObject): BarcodeStyle {
    val style = BarcodeStyle.getStyle()
    if (json.has("dotWidth")) style.setDotWidth(json.getInt("dotWidth"))
    if (json.has("barHeight")) style.setBarHeight(json.getInt("barHeight"))
    if (json.has("readable")) style.setReadable(HumanReadable.valueOf(json.getString("readable")))
    if (json.has("symbology")) style.setSymbology(Symbology.valueOf(json.getString("symbology")))
    if (json.has("align")) style.setAlign(Align.valueOf(json.getString("align")))
    if (json.has("posX")) style.setPosX(json.getInt("posX"))
    if (json.has("posY")) style.setPosY(json.getInt("posY"))
    if (json.has("width")) style.setWidth(json.getInt("width"))
    if (json.has("height")) style.setHeight(json.getInt("height"))
    if (json.has("rotate")) style.setRotate(Rotate.valueOf(json.getString("rotate")))
    return style
}

internal fun buildQrStyle(json: JSONObject): QrStyle {
    val style = QrStyle.getStyle()
    if (json.has("dot")) style.setDot(json.getInt("dot"))
    if (json.has("errorLevel")) style.setErrorLevel(ErrorLevel.valueOf(json.getString("errorLevel")))
    if (json.has("align")) style.setAlign(Align.valueOf(json.getString("align")))
    if (json.has("posX")) style.setPosX(json.getInt("posX"))
    if (json.has("posY")) style.setPosY(json.getInt("posY"))
    if (json.has("width")) style.setWidth(json.getInt("width"))
    if (json.has("height")) style.setHeight(json.getInt("height"))
    if (json.has("rotate")) style.setRotate(Rotate.valueOf(json.getString("rotate")))
    return style
}

internal fun buildBitmapStyle(json: JSONObject): BitmapStyle {
    val style = BitmapStyle.getStyle()
    if (json.has("algorithm")) style.setAlgorithm(ImageAlgorithm.valueOf(json.getString("algorithm")))
    if (json.has("value")) style.setValue(json.getInt("value"))
    if (json.has("align")) style.setAlign(Align.valueOf(json.getString("align")))
    if (json.has("posX")) style.setPosX(json.getInt("posX"))
    if (json.has("posY")) style.setPosY(json.getInt("posY"))
    if (json.has("width")) style.setWidth(json.getInt("width"))
    if (json.has("height")) style.setHeight(json.getInt("height"))
    return style
}
