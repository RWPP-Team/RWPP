/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.map

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@Suppress("unused", "MemberVisibilityCanBePrivate")
class XMLMap(inputStream: InputStream) {
    val root: Element
    val objectGroup: Element
    val width: Float
    val height: Float
    val tileWidth: Float
    val tileHeight: Float

    /**
     * @see addMapInfo
     */
    var mapInfoObject: MapObject? = null
        private set
    val objects: List<MapObject>
        field = mutableListOf<MapObject>()
    val document: Document = inputStream.use { factory.newDocumentBuilder().parse(it) }

    init {
        root = document.documentElement
        width = root.getAttribute("width").toFloat()
        height = root.getAttribute("height").toFloat()
        tileWidth = root.getAttribute("tilewidth").toFloat()
        tileHeight = root.getAttribute("tileheight").toFloat()

        objectGroup = (getTriggers(root.getElementsByTagName("objectgroup"))) ?: run {
            val element = document.createElement("objectgroup")
            element.setAttribute("name", "Triggers")
            root.appendChild(element)
            element
        }

        val objects = objectGroup.getElementsByTagName("object")

        for (i in 0 until objects.length) {
            val obj = objects.item(i) as Element
            val mapObject = MapObject(obj)
            if (obj.getAttribute("name") == "map_info") {
                mapInfoObject = mapObject
            }
            this.objects.add(mapObject)
        }
    }

    fun addMapObject(
        id: String,
        type: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        properties: (MutableList<Pair<String, String>>) -> Unit,
    ): MapObject = addMapObject(
        id,
        type,
        x,
        y,
        width,
        height,
        *buildList { properties(this) }.toTypedArray(),
    )

    fun addMapObject(
        id: String,
        type: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        vararg properties: Pair<String, String>,
    ): MapObject {
        val element = document.createElement("object")
        element.setAttribute("id", id)
        element.setAttribute("x", x.toString())
        element.setAttribute("y", y.toString())
        element.setAttribute("width", width.toString())
        element.setAttribute("height", height.toString())
        element.setAttribute("type", type)

        val properties2 = document.createElement("properties")

        for ((first, second) in properties) {
            val prop = document.createElement("property")
            prop.setAttribute("name", first)
            prop.setAttribute("value", second)
            properties2.appendChild(prop)
        }

        val mapObject = MapObject(element)
        objects.add(mapObject)
        element.appendChild(properties2)
        objectGroup.appendChild(element)
        return mapObject
    }

    fun addMapInfo(type: String) {
        if (mapInfoObject == null) {
            val element = document.createElement("object")
            element.setAttribute("name", "map_info")
            element.setAttribute("x", 0.toString())
            element.setAttribute("y", 0.toString())
            element.setAttribute("width", tileWidth.toString())
            element.setAttribute("height", tileHeight.toString())
            val properties = document.createElement("properties")
            val typeProperty = document.createElement("property")
            typeProperty.setAttribute("name", "type")
            typeProperty.setAttribute("value", type)
            properties.appendChild(typeProperty)
            element.appendChild(properties)
            objects.add(MapObject(element))
            objectGroup.appendChild(element)
            mapInfoObject = MapObject(element)
        }
    }

    fun removeMapObject(id: String) {
        val objects = objectGroup.getElementsByTagName("object")
        for (i in 0 until objects.length) {
            val obj = objects.item(i) as Element
            if (obj.getAttribute("id") == id) {
                objectGroup.removeChild(obj)
                this.objects.removeIf { it.id == id }
                break
            }
        }
    }

    fun saveToFile(path: String): File {
        addMapInfo("skirmish")
        val file = File(path)
        file.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create map directory: $parent" }
        }
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        file.outputStream().use { output ->
            transformer.transform(DOMSource(document), StreamResult(output))
        }
        return file
    }

    inner class MapObject(val element: Element) {
        var id: String
            get() = element.getAttribute("id")
            set(value) {
                element.setAttribute("id", value)
            }
        var x: Float?
            get() = element.getAttribute("x").toFloatOrNull()
            set(value) {
                element.setAttribute("x", value.toString())
            }
        var y: Float?
            get() = element.getAttribute("y").toFloatOrNull()
            set(value) {
                element.setAttribute("y", value.toString())
            }
        var width: Float?
            get() = element.getAttribute("width").toFloatOrNull()
            set(value) {
                element.setAttribute("width", value.toString())
            }
        var height: Float?
            get() = element.getAttribute("height").toFloatOrNull()
            set(value) {
                element.setAttribute("height", value.toString())
            }
        var type: String
            get() = element.getAttribute("type")
            set(value) {
                element.setAttribute("type", value)
            }
        var name: String
            get() = element.getAttribute("name")
            set(value) {
                element.setAttribute("name", value)
            }

        private fun propertiesElement(): Element =
            (element.getElementsByTagName("properties").item(0) as? Element)
                ?: document.createElement("properties").also(element::appendChild)

        /** Only property elements; pretty-printed TMX whitespace is not a property. */
        val properties: NodeList
            get() = propertiesElement().getElementsByTagName("property")

        fun appendProperty(name: String, value: String) {
            val prop = document.createElement("property")
            prop.setAttribute("name", name)
            prop.setAttribute("value", value)
            propertiesElement().appendChild(prop)
        }

        fun removeProperty(name: String) {
            val container = propertiesElement()
            val properties = properties
            for (i in properties.length - 1 downTo 0) {
                val property = properties.item(i) as Element
                if (property.getAttribute("name") == name) {
                    container.removeChild(property)
                }
            }
        }

        fun getProperty(name: String): String? {
            val properties = properties
            for (i in 0 until properties.length) {
                val property = properties.item(i) as Element
                if (property.getAttribute("name") == name) {
                    return if (property.hasAttribute("value")) property.getAttribute("value") else property.textContent
                }
            }
            return null
        }

        fun setProperty(name: String, value: String) {
            val properties = properties
            for (i in 0 until properties.length) {
                val property = properties.item(i) as Element
                if (property.getAttribute("name") == name) {
                    property.setAttribute("value", value)
                    return
                }
            }
            appendProperty(name, value)
        }

        fun hasProperty(name: String): Boolean {
            val properties = properties
            for (i in 0 until properties.length) {
                val property = properties.item(i) as Element
                if (property.getAttribute("name") == name) {
                    return true
                }
            }
            return false
        }

        @Suppress("unused")
        fun parseProperties(): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            for (i in 0 until properties.length) {
                val property = properties.item(i) as Element
                val name = property.getAttribute("name")
                val value = if (property.hasAttribute("value")) property.getAttribute("value") else property.textContent
                result.add(name to value)
            }
            return result
        }
    }

    private fun getTriggers(list: NodeList): Element? {
        val length = list.length
        for (i in 0 until length) {
            val item = list.item(i) as Element?
            if (item?.nodeName == "objectgroup" && item.getAttribute("name") == "Triggers") {
                return item
            }
        }
        return null
    }

    companion object {
        private val factory = DocumentBuilderFactory.newInstance()
    }
}
