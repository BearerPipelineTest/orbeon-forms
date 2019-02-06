/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.control

import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms.analysis.ControlAnalysisFactory.ValueControl
import org.orbeon.oxf.xforms.control.XFormsValueControl._
import org.orbeon.oxf.xforms.event.XFormsEvent
import org.orbeon.oxf.xforms.event.events.XXFormsValueEvent
import org.orbeon.oxf.xforms.model.{DataModel, XFormsModelBinds}
import org.orbeon.oxf.xforms.processor.handlers.XFormsBaseHandler
import org.orbeon.oxf.xforms.processor.handlers.xhtml.XFormsBaseHandlerXHTML
import org.orbeon.oxf.xforms.state.ControlState
import org.orbeon.oxf.xml.XMLConstants._
import org.orbeon.oxf.xml.{NamespaceMapping, XMLReceiver, XMLReceiverHelper}
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.Implicits._
import org.xml.sax.helpers.AttributesImpl

import scala.collection.JavaConverters._

// Trait for for all controls that hold a value
trait XFormsValueControl extends XFormsSingleNodeControl {

  override type Control <: ValueControl

  // Value
  private[XFormsValueControl] var _value: String = null // TODO: use ControlProperty<String>?

  // Previous value for refresh
  private[XFormsValueControl] var _previousValue: String = null

  // External value (evaluated lazily)
  private[XFormsValueControl] var isExternalValueEvaluated: Boolean = false
  private[XFormsValueControl] var externalValue: String = null

  def handleExternalValue = true

  override def onCreate(restoreState: Boolean, state: Option[ControlState]): Unit = {
    super.onCreate(restoreState, state)

    _value = null
    _previousValue = null

    markExternalValueDirty()
  }

  override def evaluateImpl(relevant: Boolean, parentRelevant: Boolean): Unit = {

    // Evaluate other aspects of the control if necessary
    super.evaluateImpl(relevant, parentRelevant)

    // Evaluate control values
    if (relevant) {
      // Control is relevant
      // NOTE: Ugly test on staticControl is to handle the case of xf:output within LHHA
      if ((_value eq null) || (staticControl eq null) || containingDocument.getXPathDependencies.requireValueUpdate(staticControl, effectiveId)) {
        evaluateValue()
        markExternalValueDirty()
      }
    } else {
      // Control is not relevant
      isExternalValueEvaluated = true
      externalValue = null
      _value = null
    }

    // NOTE: We no longer evaluate the external value here, instead we do lazy evaluation. This is good in particular when there
    // are multiple refreshes during an Ajax request, and LHHA values are only needed in the end.
  }

  def evaluateValue(): Unit =
    setValue(DataModel.getValue(boundItemOpt))

  def evaluateExternalValue(): Unit =
    setExternalValue(
      if (handleExternalValue)
        getValue // by default, same as value
      else
        null
    )

  protected def markExternalValueDirty(): Unit = {
    isExternalValueEvaluated = false
    externalValue = null
  }

  protected def isExternalValueDirty: Boolean =
    ! isExternalValueEvaluated

  override def isValueChangedCommit(): Boolean = {
    val result = _previousValue != _value
    _previousValue = _value
    result
  }

  // This usually doesn't need to be overridden (only XFormsUploadControl as of 2012-08-15)
  def storeExternalValue(externalValue: String) =
    if (handleExternalValue) // Q: Should throw if not allowed?
      doStoreExternalValue(externalValue)

  // Subclasses can override this to translate the incoming external value
  def translateExternalValue(externalValue: String): Option[String] = Option(externalValue)

  // Set the external value into the instance
  final def doStoreExternalValue(externalValue: String): Unit = {
    // NOTE: Standard value controls should be bound to simple content only. Is there anything we should / can do
    // about this? See: https://github.com/orbeon/orbeon-forms/issues/13

    boundNodeOpt match {
      case None ⇒
        // This should not happen
        throw new OXFException("Control is no longer bound to a node. Cannot set external value.")
      case Some(boundNode) ⇒
        translateExternalValue(externalValue) foreach { translatedValue ⇒
          DataModel.setValueIfChangedHandleErrors(
            containingDocument = containingDocument,
            eventTarget        = this,
            locationData       = getLocationData,
            nodeInfo           = boundNode,
            valueToSet         = translatedValue,
            source             = "client",
            isCalculate        = false
          )
        }
    }

    // NOTE: We do *not* call evaluate() here, as that will break the difference engine. doSetValue() above marks
    // the controls as dirty, and they will be evaluated when necessary later.
  }

  final protected def getValueUseFormat(format: Option[String]) =
    format flatMap valueWithSpecifiedFormat orElse valueWithDefaultFormat

  // Convenience method for handler: return a formatted value for read-only output
  def getFormattedValue: Option[String] = Option(getExternalValue())

  // Format value according to format attribute
  final protected def valueWithSpecifiedFormat(format: String): Option[String] = {
    assert(isRelevant)
    assert(getValue ne null)

    evaluateAsString(format, Seq(stringToStringValue(getValue)), 1)
  }

  // Try default format for known types
  final protected def valueWithDefaultFormat: Option[String] = {
    assert(isRelevant)
    assert(getValue ne null)

    def evaluateFormat(format: String) =
      evaluateAsString(
         format,
         Some(stringToStringValue(getValue)),
         FormatNamespaceMapping,
         bindingContext.getInScopeVariables
       )

    for {
      typeName ← getBuiltinTypeNameOpt
      format   ← containingDocument.getTypeOutputFormat(typeName)
      value    ← evaluateFormat(format)
    } yield
      value
  }

  /**
   * Return the control's internal value.
   */
  final def getValue = _value
  final def isEmptyValue = XFormsModelBinds.isEmptyValue(_value)

  /**
   * Return the control's external value is the value as exposed to the UI layer.
   */
  final def getExternalValue(): String = {
    if (! isExternalValueEvaluated) {
      if (isRelevant)
        evaluateExternalValue()
      else
        // NOTE: if the control is not relevant, nobody should ask about this in the first place
        setExternalValue(null)

      isExternalValueEvaluated = true
    }
    externalValue
  }

  final def externalValueOpt = Option(getExternalValue)

  /**
   * Return the external value ready to be inserted into the client after an Ajax response.
   */
  def getRelevantEscapedExternalValue = getExternalValue
  def getNonRelevantEscapedExternalValue = ""

  final def getEscapedExternalValue =
    if (isRelevant)
      // NOTE: Not sure if it is still possible to have a null value when the control is relevant
      Option(getRelevantEscapedExternalValue) getOrElse ""
    else
      // Some controls don't have "" as non-relevant value
      getNonRelevantEscapedExternalValue

  protected final def setValue(value: String): Unit =
    this._value = value

  protected final def setExternalValue(externalValue: String): Unit =
    this.externalValue = externalValue

  override def getBackCopy: AnyRef = {
    // Evaluate lazy values
    getExternalValue()
    super.getBackCopy
  }

  final override def compareExternalMaybeClientValue(
    previousValueOpt   : Option[String],
    previousControlOpt : Option[XFormsControl]
  ): Boolean =
    // NOTE: Call `compareExternalUseExternalValue` directly so as to avoid check on
    // `(previousControlOpt exists (_ eq this)) && (getInitialLocal eq getCurrentLocal)` which
    // causes part of https://github.com/orbeon/orbeon-forms/issues/2857.
    compareExternalUseExternalValue(previousValueOpt orElse externalValueOpt, previousControlOpt)

  override def compareExternalUseExternalValue(
    previousExternalValue : Option[String],
    previousControl       : Option[XFormsControl]
  ): Boolean =
    handleExternalValue && (
      previousControl match {
        case Some(other: XFormsValueControl) ⇒
          previousExternalValue == other.externalValueOpt &&
          super.compareExternalUseExternalValue(previousExternalValue, previousControl)
        case _ ⇒ false
      }
    )

  final def outputAjaxDiffMaybeClientValue(
    clientValue           : Option[String],
    previousControl       : Option[XFormsValueControl])(implicit
    receiver              : XMLReceiver
  ): Unit =
    outputAjaxDiffUseClientValue(
      if (handleExternalValue)
        clientValue orElse (previousControl map (_.getExternalValue))
      else
        None,
      previousControl,
      None)(
      new XMLReceiverHelper(receiver)
    )

  def mustOutputAjaxValueChange(
    previousValue   : Option[String],
    previousControl : Option[XFormsValueControl]
  ): Boolean =
    previousControl.isEmpty && getEscapedExternalValue != getNonRelevantEscapedExternalValue ||
    previousControl.nonEmpty && ! (previousValue contains getExternalValue)                  ||
    (previousControl exists (! _.isReadonly)) && isReadonly // https://github.com/orbeon/orbeon-forms/issues/3130

  def outputAjaxDiffUseClientValue(
    previousValue   : Option[String],
    previousControl : Option[XFormsValueControl],
    content         : Option[XMLReceiverHelper ⇒ Unit])(implicit
    ch              : XMLReceiverHelper
  ): Unit = {

    val hasNestedValueContent =
      handleExternalValue && mustOutputAjaxValueChange(previousValue, previousControl)

    val hasNestedContent =
      content.isDefined || hasNestedValueContent

    val outputNestedContent = (ch: XMLReceiverHelper) ⇒ {

      content foreach (_(ch))

      if (hasNestedValueContent)
        outputValueElement(
          attributesImpl = new AttributesImpl,
          elementName    = "value",
          value          = getEscapedExternalValue
        )(ch)
    }

    super.outputAjaxDiff(
      previousControl,
      hasNestedContent option outputNestedContent
    )

    outputAriaByAtts(previousValue, previousControl, ch)
  }

  // This logic applies only when a control comes into existence. At that point, we must tell the client, when
  //
 final def outputAriaByAtts(
    previousValue   : Option[String],
    previousControl : Option[XFormsValueControl],
    ch              : XMLReceiverHelper
  ): Unit =
    if (previousControl.isEmpty && ! isStaticReadonly) {
      for {
        (lhha, attName)          ← ControlAjaxSupport.LhhaWithAriaAttName
        value                    ← ControlAjaxSupport.findAriaBy(staticControlOpt.get, Some(this), lhha, condition = _.isForRepeat)(containingDocument)
        ariaByControlEffectiveId ← findAriaByControlEffectiveId
      } locally {
        ControlAjaxSupport.outputAttributeElement(
          previousControl,
          this,
          ariaByControlEffectiveId,
          attName,
          _ ⇒ value
        )(ch, containingDocument)
      }
    }

  // Can be overridden by subclasses
  // This is the effective id of the element which may have an `aria-labelledby`, etc. attribute. So an `<input>`, `<textarea>`,
  // etc. HTML element id. This is needed so that the Ajax update can know how to update the attribute.
  def findAriaByControlEffectiveId: Option[String] =
    Some(XFormsBaseHandler.getLHHACId(containingDocument, effectiveId, XFormsBaseHandlerXHTML.ControlCode))

  protected def outputValueElement(
    attributesImpl : AttributesImpl,
    elementName    : String,
    value          : String)(implicit
    ch             : XMLReceiverHelper
  ): Unit = {
    ch.startElement("xxf", XXFORMS_NAMESPACE_URI, elementName, attributesImpl)
    if (value.nonEmpty)
      ch.text(value)
    ch.endElement()
  }

  override def performDefaultAction(event: XFormsEvent): Unit = event match {
    case xxformsValue: XXFormsValueEvent ⇒ storeExternalValue(xxformsValue.value)
    case _ ⇒ super.performDefaultAction(event)
  }

  override def toXML(helper: XMLReceiverHelper, attributes: List[String])(content: ⇒ Unit): Unit =
    super.toXML(helper, attributes) {
      helper.text(getExternalValue())
    }

  override def writeMIPs(write: (String, String) ⇒ Unit): Unit = {
    super.writeMIPs(write)

    if (isRequired)
      write("required-and-empty", XFormsModelBinds.isEmptyValue(getValue).toString)
  }

  override def addAjaxAttributes(attributesImpl: AttributesImpl, previousControlOpt: Option[XFormsControl]): Boolean = {

    var added = super.addAjaxAttributes(attributesImpl, previousControlOpt)

    // NOTE: We should really have a general mechanism to add/remove/diff classes.
    if (isRequired) {

      val control1Opt = previousControlOpt.asInstanceOf[Option[XFormsValueControl]]
      val control2    = this

      val control2IsEmptyValue = control2.isEmptyValue

      if (control1Opt.isEmpty || control1Opt.exists(control1 ⇒ ! control1.isRequired || control1.isEmptyValue != control2IsEmptyValue)) {
        attributesImpl.addAttribute("", "empty", "empty", XMLReceiverHelper.CDATA, control2IsEmptyValue.toString)
        added = true
      }
    }

    added
  }
}

object XFormsValueControl {

  val FormatNamespaceMapping =
    NamespaceMapping(
      Map(
        XSD_PREFIX           → XSD_URI,
        XFORMS_PREFIX        → XFORMS_NAMESPACE_URI,
        XFORMS_SHORT_PREFIX  → XFORMS_NAMESPACE_URI,
        XXFORMS_PREFIX       → XXFORMS_NAMESPACE_URI,
        XXFORMS_SHORT_PREFIX → XXFORMS_NAMESPACE_URI,
        EXFORMS_PREFIX       → EXFORMS_NAMESPACE_URI
      ).asJava)
}