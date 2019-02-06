/**
 * Copyright (C) 2018 Orbeon, Inc.
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
package org.orbeon.xforms

import org.orbeon.liferay._
import org.orbeon.xforms.facade.Init
import org.scalajs.dom

import scala.concurrent.duration._
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global ⇒ g}

trait App {

  def load(): Unit

  def main(args: Array[String]): Unit = {

    def loadAndInit(): Unit = {
      load()
      Init.document()
    }

    def waitForInitDataAndThenInitialize(): Unit = {

      if (js.isUndefined(g.orbeonInitData))
        js.timers.setTimeout(100.milliseconds) {
          waitForInitDataAndThenInitialize()
        }
      else
        loadAndInit()
    }

    $(() ⇒ {
      dom.window.Liferay.toOption match {
        case None          ⇒ waitForInitDataAndThenInitialize()
        case Some(liferay) ⇒ liferay.on("allPortletsReady", waitForInitDataAndThenInitialize _)
      }
    })
  }

}
