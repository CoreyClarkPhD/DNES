package edu.duke.cs.banjo.learner.components;

import edu.duke.cs.banjo.bayesnet.*;
import edu.duke.cs.banjo.data.settings.Settings;
import edu.duke.cs.banjo.utility.BANJO;
import edu.duke.cs.banjo.utility.BanjoException;

import java.util.*;

/**
 * Proposes a list of all BayesNetChanges that can be applied in a single step (using
 * addition, deletion, or reversal of a single edge), based on the current network 
 * configuration.
 * 
 * <p><strong>Details:</strong> <br>
 *  
 * <p><strong>Change History:</strong> <br>
 * Created on Dec 20, 2004
 * <p>
 * 8/25/2005 (v1.0.1) hjs	Add conditions to check proposed changes against maxParentCount.
 * <br>
 * 2/15/2006 (v2.0) hjs	    Properly enable reversals of edges between any 2 nodes of lag 0.
 * <br>
 * 4/15/2008 (v2.2) hjs     Add additional condition for selecting reversals.
 * 
 * @author Jurgen Sladeczek (hjs) <br>
 * For the latest info, please visit www.cs.duke.edu.
 */
public class ProposerAdd extends Proposer {
	
	protected final int bayesNetChangeSelectLimit;

    private abstract class multipleMovesStructureSelector extends StructureSelector {
        
         public abstract List suggestBayesNetChanges(
                final BayesNetManagerI _bayesNetManager ) throws Exception;
         
         public BayesNetChangeI suggestBayesNetChange(
                 final BayesNetManagerI _bayesNetManager ) throws Exception {
             
             // This global proposer cannot be used to supply a single bayesNetChange only
             throw new BanjoException( 
                     BANJO.ERROR_BANJO_DEV,
                     "(ProposerAllLocalMoves.suggestBayesNetChange) " +
                     "This method is not available (by design). " +
                     "Use 'suggestBayesNetChanges' instead." );
         }
    }
    
    protected class EdgesAsMatrixSelector extends multipleMovesStructureSelector {

        /**
         * @return Returns the list of bayesNetChanges.
         */
        public List suggestBayesNetChanges(
                final BayesNetManagerI _bayesNetManager ) throws Exception {
            

          // Clear the list of BayesNetChanges
          changeList.clear();
          
          int[][] parentIDlist;
          
          // Collect all possible additions from the addableEdges
          EdgesWithCachedStatisticsI addableParents = 
              _bayesNetManager.getAddableParents();
          
        
          
          // Used for checking against the parent count limit 
          EdgesWithCachedStatisticsI currentParents = 
              _bayesNetManager.getCurrentParents();
          
          int typeCount;
                  
          // For each node, get its parentIDlist, then add a bayesNetChange 
          // for each addable parent
          for (int i=0; i< varCount; i++) {
              
              // hjs 8/23/05
              // Only add parents if we don't bump against the parentCount limit
              // for variable i
              if ( currentParents.getParentCount( i ) < maxParentCount ) {
                  
                  parentIDlist = addableParents.getCurrentParentIDlist(i, 0);
                             
                  for ( int j=0; j<parentIDlist.length; j++ ) {
                  
                      changeList.add( new BayesNetChange( i,  
                              parentIDlist[j][0], parentIDlist[j][1],
                              BANJO.CHANGETYPE_ADDITION ) );
                  }
              }
          }
          typeCount = changeList.size();
          proposedChangeTypeTracker[BANJO.CHANGETYPE_ADDITION] += typeCount;
  

                  
          return changeList;
      }
    
      public BayesNetChangeI suggestBayesNetChange(
              final BayesNetManagerI _bayesNetManager ) throws Exception {
          
          // This global proposer cannot be used to supply a single bayesNetChange only
          throw new BanjoException( 
                  BANJO.ERROR_BANJO_DEV,
                  "(ProposerAllLocalMoves.suggestBayesNetChange) " +
                  "This method is not available (by design). " +
                  "Use 'suggestBayesNetChanges' instead." );
      }
    }

    protected class EdgesAsArraySelector extends multipleMovesStructureSelector {

        /**
         * @return Returns the list of bayesNetChanges.
         */
        public List suggestBayesNetChanges(
                final BayesNetManagerI _bayesNetManager ) throws Exception {
          
          int[][] parentIDlist;
          
          int dimVariables;
          int dimParents;
          int dimLags;
          int offsetVariables;
          int offsetParents;
          int offsetLags;
          
          dimVariables = varCount;
          dimParents = varCount;
          dimLags = maxMarkovLag - minMarkovLag + 1;
          
          // Parents are listed consecutively within each lag:
          offsetParents = 1;
          // The index "offset" between consecutive lags:
          offsetLags = dimParents;
          // The index "offset" between consecutive variables:
          offsetVariables = dimParents * dimLags;
          
          // Clear the list of BayesNetChanges
          changeList.clear();
            
          // Collect all possible additions from the addableEdges
          EdgesAsArrayWithCachedStatistics addableParents = 
              ( EdgesAsArrayWithCachedStatistics ) _bayesNetManager.getAddableParents();
          
          
          // Used for checking against the parent count limit 
          EdgesAsArrayWithCachedStatistics currentParents = 
              ( EdgesAsArrayWithCachedStatistics ) _bayesNetManager.getCurrentParents();
          
          int typeCount;
                  
          // For each node, get its parentIDlist, then add a bayesNetChange 
          // for each addable parent
          for (int i=0; i< varCount; i++) {
              
              // hjs 8/23/05
              // Only add parents if we don't bump against the parentCount limit
              // for variable i
              if ( currentParents.getParentCount( i ) < maxParentCount ) {
                  
                  parentIDlist = addableParents.getCurrentParentIDlist(i, 0);
                  int length = parentIDlist.length;            
                  for ( int j=0; j<length; j++ ) {
                  
                      changeList.add( new BayesNetChange( i,  
                              parentIDlist[j][0], parentIDlist[j][1],
                              BANJO.CHANGETYPE_ADDITION ) );
                  }
              }
          }
          typeCount = changeList.size();
          proposedChangeTypeTracker[BANJO.CHANGETYPE_ADDITION] += typeCount;
  
          return changeList;
        }
    }

    /**
     * @return Returns the bayesNetChange.
     */
    public BayesNetChangeI suggestBayesNetChange(
        final BayesNetManagerI _bayesNetManager ) throws Exception {
        
        return structureSelector.suggestBayesNetChange( _bayesNetManager );
    }
		
	// Constructor, using the base class constructor
	public ProposerAdd( BayesNetManagerI initialBayesNet, 
			Settings processData ) throws Exception {
		
		super(initialBayesNet, processData);
        
        if ( BANJO.CONFIG_PARENTSETS.equalsIgnoreCase( 
                BANJO.UI_PARENTSETSASARRAYS ) ) {
        
            structureSelector = new EdgesAsArraySelector();
        }
        else if ( BANJO.CONFIG_PARENTSETS.equalsIgnoreCase( 
                BANJO.UI_PARENTSETSASMATRICES ) ) {
        
            structureSelector = new EdgesAsMatrixSelector();
        }
        else {
            
            throw new BanjoException( 
                    BANJO.ERROR_BANJO_DEV,
                    "(ProposerAllLocalMoves constructor) " +
                    "Development issue: There is no code for handling the supplied type " +
                    "of parent sets." );
        }
		
		// set the limit for the number of attempts to select a bayesNetChange
		bayesNetChangeSelectLimit = BANJO.LIMITFORTRIES;
		
		// Create the list for holding all possible changes for the step
		changeList = new ArrayList();
	}
    
    public List suggestBayesNetChanges(
            final BayesNetManagerI _bayesNetManager ) throws Exception {
                
        return structureSelector.suggestBayesNetChanges( _bayesNetManager );        
    }
}