/**
 * Copyright (C) 2016 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.relational.index

import org.orbeon.oxf.fr.FormRunnerCommon._
import org.orbeon.oxf.fr.XMLNames._
import org.orbeon.oxf.fr.{DataFormatVersion, FormRunner, InDocFormRunnerDocContext}
import org.orbeon.oxf.util.MarkupUtils._
import org.orbeon.saxon.om
import org.orbeon.saxon.om.{DocumentInfo, NodeInfo}
import org.orbeon.scaxon.NodeConversions._
import org.orbeon.scaxon.SimplePath._


trait FormDefinition {

  private val FRSummary       = "fr-summary"
  private val FRSearch        = "fr-search"
  private val FRIndex         = "fr-index"

  private val ClassesPredicate = Set(FRSummary, FRSearch, FRIndex)

  // Returns the controls that are searchable from a form definition
  def findIndexedControls(
    formDoc                   : DocumentInfo,
    databaseDataFormatVersion : DataFormatVersion
  ): Seq[IndexedControl] = {

    implicit val ctx = new InDocFormRunnerDocContext(formDoc) {
      override lazy val bodyElemOpt: Option[om.NodeInfo] = {

        // For "Form Builder as form definition". This is used by the Summary page and the `reindex()` feature.
        frc.findFormRunnerBodyElem(formDoc) orElse
          (formDoc.rootElement / "*:body" / "*:div" find (_.id == "fb-pseudo-body"))
      }
    }

    // Controls in the view, with the `fr-summary` or `fr-search` class
    val indexedControlBindPathHolders =
      FormRunner.searchControlsInFormByClass(ClassesPredicate, databaseDataFormatVersion)

    indexedControlBindPathHolders map { case ControlBindPathHoldersResources(control, bind, path, _, resources) =>
      IndexedControl(
        name      = FormRunner.getControlName(control),
        inSearch  = control.attClasses(FRSearch),
        inSummary = control.attClasses(FRSummary),
        xpath     = path map (_.value) mkString "/",
        xsType    = (bind /@ "type" map (_.stringValue)).headOption getOrElse "xs:string",
        control   = control.localname,
        htmlLabel = FormRunner.hasHTMLMediatype(control / (XF -> "label")),
        resources = resources.toList
      )
    }
  }

  case class IndexedControl(
    name      : String,
    inSearch  : Boolean,
    inSummary : Boolean,
    xpath     : String,
    xsType    : String,
    control   : String,
    htmlLabel : Boolean,
    resources : List[(String, NodeInfo)]
  ) {
    def toXML: NodeInfo = {

      def matchForControl(control: String): String =
        if (control == "input" || control == "textarea")
          "substring"
        else if (control == "select")
          "token"
        else if (control == "select1")
          "exact"
        else
          "substring"

      <query
        name={name}
        path={xpath}
        type={xsType}
        control={control}
        search-field={inSearch.toString}
        summary-field={inSummary.toString}
        match={matchForControl(control)}
        html-label={htmlLabel.toString}>{
        for ((lang, resourceHolder) <- resources)
          yield
            <resources lang={lang}>{
              val labelElemOpt =
                resourceHolder elemValueOpt "label" map { label =>
                  <label>{if (htmlLabel) label else label.escapeXmlMinimal}</label>
                }

              val itemElems = resourceHolder child "item" map { item =>
                <item>{
                  <label>{item elemValue "label"}</label>
                  <value>{item elemValue "value"}</value>
                }</item>
              }

              labelElemOpt.toList ++ itemElems
            }</resources>
      }</query>
    }
  }
}
