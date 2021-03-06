/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.hops.rewrite;

import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysml.hops.Hop;
import org.apache.sysml.hops.HopsException;
import org.apache.sysml.hops.IndexingOp;
import org.apache.sysml.hops.LeftIndexingOp;
import org.apache.sysml.hops.LiteralOp;

/**
 * Rule: Indexing vectorization. This rewrite rule set simplifies
 * multiple right / left indexing accesses within a DAG into row/column
 * index accesses, which is beneficial for two reasons: (1) it is an 
 * enabler for later row/column partitioning, and (2) it reduces the number
 * of operations over potentially large data (i.e., prevents unnecessary MR 
 * operations and reduces pressure on the buffer pool due to copy on write
 * on left indexing).
 * 
 */
public class RewriteIndexingVectorization extends HopRewriteRule
{
	
	private static final Log LOG = LogFactory.getLog(RewriteIndexingVectorization.class.getName());
	
	@Override
	public ArrayList<Hop> rewriteHopDAGs(ArrayList<Hop> roots, ProgramRewriteStatus state) 
		throws HopsException
	{
		if( roots == null )
			return roots;
		
		for( Hop h : roots )
			rule_IndexingVectorization( h );
		
		return roots;
	}

	@Override
	public Hop rewriteHopDAG(Hop root, ProgramRewriteStatus state) 
		throws HopsException
	{
		if( root == null )
			return root;
		
		rule_IndexingVectorization( root );
		
		return root;
	}

	private void rule_IndexingVectorization( Hop hop ) 
		throws HopsException 
	{
		if(hop.isVisited())
			return;
		
		//recursively process children
		for( int i=0; i<hop.getInput().size(); i++)
		{
			Hop hi = hop.getInput().get(i);
			
			//apply indexing vectorization rewrites
			//MB: disabled right indexing rewrite because (1) piggybacked in MR anyway, (2) usually
			//not too much overhead, and (3) makes literal replacement more difficult
			//vectorizeRightIndexing( hi ); //e.g., multiple rightindexing X[i,1], X[i,3] -> X[i,];
			hi = vectorizeLeftIndexing( hi );  //e.g., multiple left indexing X[i,1], X[i,3] -> X[i,]; 
			
			//process childs recursively after rewrites
			rule_IndexingVectorization( hi );
		}

		hop.setVisited();
	}

	/**
	 * Note: unnecessary row or column indexing then later removed via
	 * dynamic rewrites
	 * 
	 * @param hop high-level operator
	 * @throws HopsException if HopsException occurs
	 */
	@SuppressWarnings("unused")
	private static void vectorizeRightIndexing( Hop hop )
		throws HopsException
	{
		if( hop instanceof IndexingOp ) //right indexing
		{
			IndexingOp ihop0 = (IndexingOp) hop;
			boolean isSingleRow = ihop0.isRowLowerEqualsUpper();
			boolean isSingleCol = ihop0.isColLowerEqualsUpper();
			boolean appliedRow = false;
			
			//search for multiple indexing in same row
			if( isSingleRow && isSingleCol ){
				Hop input = ihop0.getInput().get(0);
				//find candidate set
				//dependence on common subexpression elimination to find equal input / row expression
				ArrayList<Hop> ihops = new ArrayList<Hop>();
				ihops.add(ihop0);
				for( Hop c : input.getParent() ){
					if( c != ihop0 && c instanceof IndexingOp && c.getInput().get(0) == input
					   && ((IndexingOp) c).isRowLowerEqualsUpper() 
					   && c.getInput().get(1)==ihop0.getInput().get(1) )
					{
						ihops.add( c );
					}
				}
				//apply rewrite if found candidates
				if( ihops.size() > 1 ){
					//new row indexing operator
					IndexingOp newRix = new IndexingOp("tmp", input.getDataType(), input.getValueType(), input, 
							            ihop0.getInput().get(1), ihop0.getInput().get(1), new LiteralOp(1), 
							            HopRewriteUtils.createValueHop(input, false), true, false); 
					HopRewriteUtils.setOutputParameters(newRix, -1, -1, input.getRowsInBlock(), input.getColsInBlock(), -1);
					newRix.refreshSizeInformation();
					//rewire current operator and all candidates
					for( Hop c : ihops ) {
						HopRewriteUtils.removeChildReference(c, input); //input data
						HopRewriteUtils.addChildReference(c, newRix, 0);
						HopRewriteUtils.removeChildReferenceByPos(c, c.getInput().get(1),1); //row lower expr
						HopRewriteUtils.addChildReference(c, new LiteralOp(1), 1);
						HopRewriteUtils.removeChildReferenceByPos(c, c.getInput().get(2),2); //row upper expr
						HopRewriteUtils.addChildReference(c, new LiteralOp(1), 2);
						c.refreshSizeInformation();
					}
					
					appliedRow = true;
					LOG.debug("Applied vectorizeRightIndexingRow");
				}
			}
			
			//search for multiple indexing in same col
			if( isSingleRow && isSingleCol && !appliedRow ){
				Hop input = ihop0.getInput().get(0);
				//find candidate set
				//dependence on common subexpression elimination to find equal input / row expression
				ArrayList<Hop> ihops = new ArrayList<Hop>();
				ihops.add(ihop0);
				for( Hop c : input.getParent() ){
					if( c != ihop0 && c instanceof IndexingOp && c.getInput().get(0) == input
					   && ((IndexingOp) c).isColLowerEqualsUpper() 
					   && c.getInput().get(3)==ihop0.getInput().get(3) )
					{
						ihops.add( c );
					}
				}
				//apply rewrite if found candidates
				if( ihops.size() > 1 ){
					//new row indexing operator
					IndexingOp newRix = new IndexingOp("tmp", input.getDataType(), input.getValueType(), input, 
							         new LiteralOp(1), HopRewriteUtils.createValueHop(input, true),
				                    ihop0.getInput().get(3), ihop0.getInput().get(3), false, true); 
					HopRewriteUtils.setOutputParameters(newRix, -1, -1, input.getRowsInBlock(), input.getColsInBlock(), -1);
					newRix.refreshSizeInformation();
					//rewire current operator and all candidates
					for( Hop c : ihops ) {
						HopRewriteUtils.removeChildReference(c, input); //input data
						HopRewriteUtils.addChildReference(c, newRix, 0);
						HopRewriteUtils.replaceChildReference(c, c.getInput().get(3), new LiteralOp(1), 3); //col lower expr
						HopRewriteUtils.replaceChildReference(c, c.getInput().get(4), new LiteralOp(1), 4); //col upper expr 
						c.refreshSizeInformation();
					}

					LOG.debug("Applied vectorizeRightIndexingCol");
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private static Hop vectorizeLeftIndexing( Hop hop )
		throws HopsException
	{
		Hop ret = hop;
		
		if( hop instanceof LeftIndexingOp ) //left indexing
		{
			LeftIndexingOp ihop0 = (LeftIndexingOp) hop;
			boolean isSingleRow = ihop0.isRowLowerEqualsUpper();
			boolean isSingleCol = ihop0.isColLowerEqualsUpper();
			boolean appliedRow = false;
			
			if( isSingleRow && isSingleCol )
			{
				//collect simple chains (w/o multiple consumers) of left indexing ops
				ArrayList<Hop> ihops = new ArrayList<>();
				ihops.add(ihop0);
				Hop current = ihop0;
				while( current.getInput().get(0) instanceof LeftIndexingOp ) {
					LeftIndexingOp tmp = (LeftIndexingOp) current.getInput().get(0);
					if(    tmp.getParent().size()>1  //multiple consumers, i.e., not a simple chain
						|| !((LeftIndexingOp) tmp).isRowLowerEqualsUpper() //row merge not applicable
						|| tmp.getInput().get(2) != ihop0.getInput().get(2) //not the same row
						|| tmp.getInput().get(0).getDim2() <= 1 ) //target is single column or unknown 
					{
						break;
					}
					ihops.add( tmp );
					current = tmp;
				}
				
				//apply rewrite if found candidates
				if( ihops.size() > 1 ){
					Hop input = current.getInput().get(0);
					Hop rowExpr = ihop0.getInput().get(2); //keep before reset
					
					//new row indexing operator
					IndexingOp newRix = new IndexingOp("tmp1", input.getDataType(), input.getValueType(), 
						input, rowExpr, rowExpr, new LiteralOp(1), 
						HopRewriteUtils.createValueHop(input, false), true, false); 
					HopRewriteUtils.setOutputParameters(newRix, -1, -1, input.getRowsInBlock(), input.getColsInBlock(), -1);
					newRix.refreshSizeInformation();
					//reset visit status of copied hops (otherwise hidden by left indexing)
					for( Hop c : newRix.getInput() )
						c.resetVisitStatus();
					
					//rewrite bottom left indexing operator
					HopRewriteUtils.removeChildReference(current, input); //input data
					HopRewriteUtils.addChildReference(current, newRix, 0);
					
					//reset row index all candidates and refresh sizes (bottom-up)
					for( int i=ihops.size()-1; i>=0; i-- ) {
						Hop c = ihops.get(i);
						HopRewriteUtils.replaceChildReference(c, c.getInput().get(2), new LiteralOp(1), 2); //row lower expr
						HopRewriteUtils.replaceChildReference(c, c.getInput().get(3), new LiteralOp(1), 3); //row upper expr
						((LeftIndexingOp)c).setRowLowerEqualsUpper(true);
						c.refreshSizeInformation();
					}
					
					//new row left indexing operator (for all parents, only intermediates are guaranteed to have 1 parent)
					//(note: it's important to clone the parent list before creating newLix on top of ihop0)
					ArrayList<Hop> ihop0parents = (ArrayList<Hop>) ihop0.getParent().clone();
					ArrayList<Integer> ihop0parentsPos = new ArrayList<>();
					for( Hop parent : ihop0parents ) {
						int posp = HopRewriteUtils.getChildReferencePos(parent, ihop0);
						HopRewriteUtils.removeChildReferenceByPos(parent, ihop0, posp); //input data
						ihop0parentsPos.add(posp);
					}
					
					LeftIndexingOp newLix = new LeftIndexingOp("tmp2", input.getDataType(), input.getValueType(), 
						input, ihop0, rowExpr, rowExpr, new LiteralOp(1), 
						HopRewriteUtils.createValueHop(input, false), true, false); 
					HopRewriteUtils.setOutputParameters(newLix, -1, -1, input.getRowsInBlock(), input.getColsInBlock(), -1);
					newLix.refreshSizeInformation();
					//reset visit status of copied hops (otherwise hidden by left indexing)
					for( Hop c : newLix.getInput() )
						c.resetVisitStatus();
					
					for( int i=0; i<ihop0parentsPos.size(); i++ ) {
						Hop parent = ihop0parents.get(i);
						int posp = ihop0parentsPos.get(i);
						HopRewriteUtils.addChildReference(parent, newLix, posp);
					}
					
					appliedRow = true;
					ret = newLix;
					LOG.debug("Applied vectorizeLeftIndexingRow for hop "+hop.getHopID());
				}
			}
			
			if( isSingleRow && isSingleCol && !appliedRow )
			{
				
				//collect simple chains (w/o multiple consumers) of left indexing ops
				ArrayList<Hop> ihops = new ArrayList<>();
				ihops.add(ihop0);
				Hop current = ihop0;
				while( current.getInput().get(0) instanceof LeftIndexingOp ) {
					LeftIndexingOp tmp = (LeftIndexingOp) current.getInput().get(0);
					if(    tmp.getParent().size()>1  //multiple consumers, i.e., not a simple chain
						|| !((LeftIndexingOp) tmp).isColLowerEqualsUpper() //row merge not applicable
						|| tmp.getInput().get(4) != ihop0.getInput().get(4)  //not the same col
						|| tmp.getInput().get(0).getDim1() <= 1 )  //target is single row or unknown
					{
						break;
					}
					ihops.add( tmp );
					current = tmp;
				}
				
				//apply rewrite if found candidates
				if( ihops.size() > 1 ){
					Hop input = current.getInput().get(0);
					Hop colExpr = ihop0.getInput().get(4); //keep before reset
					
					//new row indexing operator
					IndexingOp newRix = new IndexingOp("tmp1", input.getDataType(), input.getValueType(), 
						input, new LiteralOp(1), HopRewriteUtils.createValueHop(input, true),            
						colExpr, colExpr, false, true); 
					HopRewriteUtils.setOutputParameters(newRix, -1, -1, input.getRowsInBlock(), input.getColsInBlock(), -1);
					newRix.refreshSizeInformation();
					//reset visit status of copied hops (otherwise hidden by left indexing)
					for( Hop c : newRix.getInput() )
						c.resetVisitStatus();
					
					//rewrite bottom left indexing operator
					HopRewriteUtils.removeChildReference(current, input); //input data
					HopRewriteUtils.addChildReference(current, newRix, 0);
					
					//reset col index all candidates and refresh sizes (bottom-up)
					for( int i=ihops.size()-1; i>=0; i-- ) {
						Hop c = ihops.get(i);
						HopRewriteUtils.replaceChildReference(c, c.getInput().get(4), new LiteralOp(1), 4); //col lower expr
						HopRewriteUtils.replaceChildReference(c, c.getInput().get(5), new LiteralOp(1), 5); //col upper expr
						((LeftIndexingOp)c).setColLowerEqualsUpper(true);
						c.refreshSizeInformation();
					}
					
					//new row left indexing operator (for all parents, only intermediates are guaranteed to have 1 parent)
					//(note: it's important to clone the parent list before creating newLix on top of ihop0)
					ArrayList<Hop> ihop0parents = (ArrayList<Hop>) ihop0.getParent().clone();
					ArrayList<Integer> ihop0parentsPos = new ArrayList<>();
					for( Hop parent : ihop0parents ) {
						int posp = HopRewriteUtils.getChildReferencePos(parent, ihop0);
						HopRewriteUtils.removeChildReferenceByPos(parent, ihop0, posp); //input data
						ihop0parentsPos.add(posp);
					}
					
					LeftIndexingOp newLix = new LeftIndexingOp("tmp2", input.getDataType(), input.getValueType(), 
						input, ihop0, new LiteralOp(1), HopRewriteUtils.createValueHop(input, true), 
						colExpr, colExpr, false, true); 
					HopRewriteUtils.setOutputParameters(newLix, -1, -1, input.getRowsInBlock(), input.getColsInBlock(), -1);
					newLix.refreshSizeInformation();
					//reset visit status of copied hops (otherwise hidden by left indexing)
					for( Hop c : newLix.getInput() )
						c.resetVisitStatus();
					
					for( int i=0; i<ihop0parentsPos.size(); i++ ) {
						Hop parent = ihop0parents.get(i);
						int posp = ihop0parentsPos.get(i);
						HopRewriteUtils.addChildReference(parent, newLix, posp);
					}
					
					ret = newLix;
					LOG.debug("Applied vectorizeLeftIndexingCol for hop "+hop.getHopID());
				}
			}
		}
		
		return ret;
	}
}
