/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 */
package org.knime.core.gateway.v0.workflow.entity;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;
import org.knime.core.gateway.entities.EntityBuilderManager;
import org.knime.core.gateway.v0.workflow.entity.*;
import org.knime.core.gateway.v0.workflow.entity.builder.*;

/**
 *
 * @author Martin Horn, University of Konstanz
 */
public class RepoNodeTemplateEntTest {

    private static Random RAND = new Random();

    @Test
    public void test() {
        List<Object> valueList = createValueList();
        RepoNodeTemplateEnt ent = createEnt(valueList);
        testEnt(ent, valueList);
    }

    static RepoNodeTemplateEnt createEnt(final List<Object> valueList) {
        RepoNodeTemplateEntBuilder builder = EntityBuilderManager.builder(RepoNodeTemplateEntBuilder.class);
			builder.setName((String) valueList.get(0));
				builder.setType((String) valueList.get(1));
				builder.setID((String) valueList.get(2));
				builder.setIconURL((String) valueList.get(3));
	        return builder.build();
    }

    static void testEnt(final RepoNodeTemplateEnt ent, final List<Object> valueList) {
			assertEquals(ent.getName(), (String) valueList.get(0));
				assertEquals(ent.getType(), (String) valueList.get(1));
				assertEquals(ent.getID(), (String) valueList.get(2));
				assertEquals(ent.getIconURL(), (String) valueList.get(3));
	    }

    static List<Object> createValueList() {
        List<Object> valueList = new ArrayList<Object>();
  	 		valueList.add("LgfJM");	
 	  	 		valueList.add("iuf2Q");	
 	  	 		valueList.add("iPM4i");	
 	  	 		valueList.add("81s9N");	
 	         return valueList;
    }

}