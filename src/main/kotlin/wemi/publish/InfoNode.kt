package wemi.publish

import wemi.util.appendTimes

@Suppress("unused")
/**
 * Info node for [wemi.Keys.publish].
 *
 * Instances create tree structure for arbitrary publishing metadata.
 *
 * By default, Wemi implements publishing to Maven 2 repositories - for those, [InfoNode] represents
 * [`pom.xml`](https://maven.apache.org/ref/3.5.2/maven-model/maven.html). Example:
 * ```
 * InfoNode("project") {
 *
 * }
 * ```
 */
class InfoNode(val name:String) {
    var text:String? = null
    private var lazyAttributes:ArrayList<Pair<String, String>>? = null
    private var lazyChildren:ArrayList<InfoNode>? = null

    val attributes:MutableList<Pair<String, String>>
        get() {
            if (lazyAttributes == null) {
                lazyAttributes = ArrayList()
            }
            return lazyAttributes!!
        }
    val children:MutableList<InfoNode>
        get() {
            if (lazyChildren == null) {
                lazyChildren = ArrayList()
            }
            return lazyChildren!!
        }

    constructor(name:String, creator:InfoNode.()->Unit) : this(name) {
        this.creator()
    }

    /**
     * Create new child with [name] and return it.
     */
    fun newChild(name:String):InfoNode {
        val child = InfoNode(name)
        children.add(child)
        return child
    }

    /**
     * Find first child with [name] and return it.
     */
    fun findChild(name:String):InfoNode? {
        val children = lazyChildren ?: return null
        for (child in children) {
            if (child.name == name) {
                return child
            }
        }
        return null
    }

    /**
     * Remove first child named [name].
     * @return true if removed, false if no such node exists
     */
    fun removeChild(name: String):Boolean {
        val children = lazyChildren?:return false
        for (index in children.indices) {
            if (children[index].name == name) {
                children.removeAt(index)
                return true
            }
        }
        return false
    }

    /**
     * Remove all children nodes named [name].
     */
    fun removeAllChildren(name: String) {
        lazyChildren?.removeAll {
            it.name == name
        }
    }

    /**
     * Remove all children nodes.
     */
    fun removeAllChildren() {
        lazyChildren = null
    }

    /**
     * Find first child named [name] and return it.
     * If no such child exists, fall back to new one.
     */
    fun child(name:String):InfoNode {
        return findChild(name) ?: newChild(name)
    }

    /**
     * Find [nth] child named [name] and return it.
     * If no such child exists, fall back to new one.
     *
     * @param nth 0 for first, 1 for second, etc.
     * @throws IllegalArgumentException when nth is negative or when the added one would not be [nth]
     */
    fun nthChild(name:String, nth:Int):InfoNode {
        if (nth < 0) {
            throw IllegalArgumentException("nth=$nth")
        }

        var found = 0
        val children = lazyChildren
        if (children != null) {
            for (child in children) {
                if (child.name == name && found++ == nth) {
                    return child
                }
            }
        }


        if (found != nth) {
            throw IllegalArgumentException("Requested ${nth}th child, but added one would be ${found}th")
        }

        return newChild(name)
    }

    fun attribute(key:String, value:String) {
        attributes.add(key to value)
    }

    /**
     * Apply [setter] on [newChild].
     */
    inline fun <Result> newChild(name:String, setter:InfoNode.()->Result):Result {
        return newChild(name).setter()
    }

    /**
     * Apply [setter] on [child].
     */
    inline fun <Result> child(name:String, setter:InfoNode.()->Result):Result {
        return child(name).setter()
    }

    /**
     * Apply [setter] on [nthChild].
     */
    inline fun <Result> nthChild(name:String, index:Int, setter:InfoNode.()->Result):Result {
        return nthChild(name, index).setter()
    }

    /**
     * Set [text] of [newChild].
     */
    fun newChild(name:String, text:String) {
        newChild(name).text = text
    }

    /**
     * Set [text] of [child].
     */
    fun child(name:String, text:String) {
        child(name).text = text
    }

    /**
     * Set [text] of [nthChild].
     */
    fun nthChild(name:String, index:Int, text:String) {
        nthChild(name, index).text = text
    }

    fun toXML(out:StringBuilder, indent:Int) {
        out.appendTimes('\t', indent).append('<').append(name)
        lazyAttributes?.forEach { (key,name) ->
            out.append(' ').append(key).append('=').append('"').append(name).append('"')
        }
        out.append('>')

        val lazyChildren = lazyChildren
        val text = text

        if (lazyChildren == null) {
            //<thing>text</thing>
            if (text != null) {
                out.append(text)
            }
        } else {
            //<thing>
            //  text
            //  <!-- other things -->
            //</thing>
            out.append('\n')
            if (text != null) {
                out.appendTimes('\t', indent+1).append(text).append('\n')
            }

            for (node in lazyChildren) {
                node.toXML(out, indent + 1)
            }
            out.appendTimes('\t', indent)
        }

        // Close
        out.append('<').append('/').append(name).append('>').append('\n')
    }

    fun toXML():CharSequence {
        val sb = StringBuilder()
        toXML(sb, 0)
        return sb
    }

    override fun toString(): String {
        return toXML().toString()
    }
}