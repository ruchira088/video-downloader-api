package com.ruchij.core.exceptions

import cats.data.NonEmptyList
import org.jsoup.nodes.Element

trait JSoupException extends Exception {
  val element: Element
}

object JSoupException {
  case class AttributeNotFoundInElementException(element: Element, attributeKey: String) extends JSoupException

  case class MultipleElementsFoundException(element: Element, css: String, result: NonEmptyList[Element])
      extends JSoupException

  case class NoMatchingElementsFoundException(element: Element, css: String) extends JSoupException {
    override def getMessage: String =
      s"""
        Unable find element for css = $css in
        $element
      """
  }

  case class TextNotFoundInElementException(element: Element) extends JSoupException
}
