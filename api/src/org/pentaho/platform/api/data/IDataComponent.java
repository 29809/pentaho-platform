/*
 * This program is free software; you can redistribute it and/or modify it under the 
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software 
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this 
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html 
 * or from the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright 2005 - 2008 Pentaho Corporation.  All rights reserved.
 *
 * @created Oct 12, 2005 
 * @author James Dixon
 */

package org.pentaho.platform.api.data;

import org.pentaho.commons.connection.IPentahoResultSet;
import org.pentaho.platform.api.engine.IComponent;

/**
 * The data component interface extends component, and adds a single
 * method - <code>getResultSet()</code>. 

 * @author mbatchel
 * @see MDXBaseComponent
 * @see SQLBaseComponent
 * @see IPentahoResultSet
 * 
 */
public interface IDataComponent extends IComponent {

  /**
   * @return Returns the resultSet that the component currently has.
   */
  public IPentahoResultSet getResultSet();

  /**
   * Disposes of resources held by the data component
   */
  public void dispose();

}
