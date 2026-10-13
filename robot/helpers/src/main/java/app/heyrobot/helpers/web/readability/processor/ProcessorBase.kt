// Vendored from Readability4J 1.0.8 (https://github.com/dankito/Readability4J, commit 170d052e), a Kotlin port of Mozilla's
// Readability.js. Apache-2.0: see NOTICE and LICENSE in this folder. Changed for Robot (0.9 web search): the package, every
// slf4j logging call removed (no logging library may see page text), built against jsoup 1.15.1.
package app.heyrobot.helpers.web.readability.processor

import app.heyrobot.helpers.web.readability.util.RegExUtil
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Contains common utils for Preprocessor and Postprocessor
 */
abstract class ProcessorBase {

    protected open fun removeNodes(element: Element, tagName: String, filterFunction: ((Element) -> Boolean)? = null) {
        element.getElementsByTag(tagName).reversed().forEach { childElement ->
            if(childElement.parentNode() != null) {
                if(filterFunction == null || filterFunction(childElement)) {
                    printAndRemove(childElement, "removeNode('$tagName')")
                }
            }
        }
    }

    // Robot: the original logged each removed node's HTML here (slf4j); nothing of a page is ever logged.
    protected open fun printAndRemove(node: Node, reason: String) {
        if(node.parent() != null) {
            node.remove()
        }
    }


    protected open fun replaceNodes(parentElement: Element, tagName: String, newTagName: String) {
        parentElement.getElementsByTag(tagName).forEach { element ->
            element.tagName(newTagName)
        }
    }


    /**
     * Finds the next element, starting from the given node, and ignoring
     * whitespace in between. If the given node is an element, the same node is
     * returned.
     */
    protected open fun nextElement(node: Node?, regEx: RegExUtil): Element? {
        var next: Node? = node

        while(next != null
                && (next is Element == false)
                && (next is TextNode && regEx.isWhitespace(next.text()))) {
            next = next.nextSibling()
        }

        return next as? Element
    }

    /**
     * Get the inner text of a node - cross browser compatibly.
     * This also strips out any excess whitespace to be found.
     */
    protected open fun getInnerText(e: Element, regEx: RegExUtil? = null, normalizeSpaces: Boolean = true): String {
        val textContent = e.text().trim()

        if(normalizeSpaces && regEx != null) {
            return regEx.normalize(textContent)
        }

        return textContent
    }

}
