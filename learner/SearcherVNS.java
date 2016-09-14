package edu.duke.cs.banjo.learner;

import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Stack;
import java.util.TreeSet;

import edu.duke.cs.banjo.bayesnet.*;
import edu.duke.cs.banjo.data.settings.*;
import edu.duke.cs.banjo.learner.Searcher.HighScoreSetUpdater;
import edu.duke.cs.banjo.learner.components.*;
import edu.duke.cs.banjo.utility.*;

public class SearcherVNS extends Searcher {
	StringBuffer out =  new StringBuffer( );
	//VNS Proposer Interfaces
	protected ProposerI proposerAdd;
	protected ProposerI proposerDelete;
	protected ProposerI proposerReverse;
	
	//VNS Search Executor 
	protected VNSExecuter vnsExecuter;
	protected VNDExecuter vndExecuter;
	
	protected NeighborhoodSearch subsearch;

	protected PostProcessor postProcessor;
	
	// Variables related to the restart of the current bayesnet
	protected long networksVisitedSinceHighScore;
	protected long networksVisitedSinceRestart;
	protected long minNetworksVisitedSinceHighScore;
	protected long minNetworksVisitedBeforeRestart;
	protected long maxNetworksVisitedBeforeRestart;
	
	//protected double currentNetworkScore;
	protected double vnsNetworkScore;		//Network Score after selection of ADD/DEL/REV Network Change
	protected double vndNetworkScore;		//Value returned after a ADD/DEL/REV execution in VND
	protected double globalMaxNetworkScore; //Largest value obtained (updated in VNS loop)
	protected double localMaxNetworkScore; 	//Largest value for given VND loop
	protected SuggestedChange suggestedChange = new SuggestedChange();
	
	protected int restartCount = 0;
	
	protected double scoreDiff = 5.0E-7d; 	//Not utilized, but was kept for float point 

	//Get copy of BANJO RNG, to see local RNG
			BanjoRandomNumber bRND = new BanjoRandomNumber();
			long seed = bRND.getBanjoSeed();
			
			//Seeding RNG with BANJO Seed passed in
			//	Utilized in VNS to grab random selection from list returned
			Random rnd = new Random(seed);
			
	protected class SuggestedChange {
		int changeID = -1;
		double networkScore = -Double.MAX_VALUE;
	}
	/**
	 * 	CONSTRUCTOR
	 */
	public SearcherVNS(Settings _processData) throws Exception {
		super( _processData );
		
		// Validate the required settings
		boolean isDataValid = validateRequiredData();
		
		// If crucial settings could not be validated, we can't execute the remaining
		// code in this constructor
		if ( !isDataValid ) return;

		// Set up the required (subordinate) objects
		setupSearch();
	}
	
	/**
	 * 		SEARCH EXECUTER
	 * 		Variable Neighborhood Search (VNS)
	 * 		Variable Neighborhood Decent (VND)
	 */
	protected class VNDExecuter extends SearchExecuter {
		
		//Stores BayesNetChanges that occur on VND Loop
		Stack<BayesNetChangeI> vndNetChanges = new Stack<BayesNetChangeI>();		
		
		//Executes VND Search Loop
		protected void executeSearch() throws Exception {
			int searches = 0;
			//Set VNS Score to localMaxNetworkScore
			localMaxNetworkScore = vnsNetworkScore;
			
			//Used to determine when to exit
			boolean betterSolutionFound = true;
			
			while(betterSolutionFound == true){
				//System.out.println("            VND Start - Local Max" + localMaxNetworkScore);
				//Adopt Current Network as Default
				adoptCurrentNetworkChanges();
				searches++;
				//out.append("	VND: Start\n");
				
				//Perform addSearch
				//betterSolutionFound = subsearch.executeAdd();
				betterSolutionFound = subsearch.executeDelete();
				
				//If addSearch network is NOT better, continue.
				if(!betterSolutionFound){
					//Restore Network To Default
					restoreNetworkToDefault();
					searches++;
					//out.append("	VND: Stage 1\n");
					//Perform deleteSearch 
					//betterSolutionFound = subsearch.executeReverse();
					betterSolutionFound = subsearch.executeReverse();
					
					//If reverseSearch network is NOT better, continue.
					if(!betterSolutionFound){
						//Restore Network To Default
						restoreNetworkToDefault();
						//out.append("	VND: Stage 2\n");
						searches++;
						//Perform reverseSearch
						//betterSolutionFound = subsearch.executeDelete();
						betterSolutionFound = subsearch.executeAdd();
						
						//If deleteSearch is better, entire loop continues
						//	otherwise, betterSolutionFound is false and loop breaks
						if(!betterSolutionFound){
							//Restore Network To Default
							searches++;
							restoreNetworkToDefault();
							out.append("	VND Subsearches Complted: " + searches + "\n");
							//System.out.println("                VND Complete - Local Max" + localMaxNetworkScore);
							
						}
					}
				}
				
				//System.out.println("              VND Better Solution Found - Local Max" + localMaxNetworkScore);
			}
						
		}
		
		//Restores network back to original state when ADD/REV/DEL are 
		//		unsuccessful in find better network
		public void restoreNetworkToDefault() throws Exception{
			//out.append("		Restoring VND Network\n");
			
			while(!this.vndNetChanges.empty()){
				//Get Change Stored in Stack
				BayesNetChangeI netChange = (BayesNetChangeI) vndNetChanges.pop();
				//Set Change Status so it can be undone
				netChange.setChangeStatus( BANJO.CHANGESTATUS_APPLIED );
				
				try{
					//Undo Change to Network
					bayesNetManager.undoChange( netChange );
					//Update Score 
					evaluator.adjustNodeScoresForUndo( netChange );
				}
				catch(BanjoException e ){
					//Change already applied, so no need to undo
					out.append("		BayesNetChange Already Processed\n");
				}
			}
		}
		
		//When VND finds a better network, it adopts changes
		// of network
		public void adoptCurrentNetworkChanges() throws Exception{
			vndNetChanges.clear();	
		}
		
		//This was part of default based from Greedy.. not used
		protected int sizeOfListOfChanges() {
		    
		    // bound on number of ("single step") local moves
		    return BANJO.CHANGETYPE_COUNT * varCount * varCount;
		}
	}
	protected class VNSExecuter extends SearchExecuter {
		
		// Holds suggested changes from respective proposers
		//	Initial design required different list, could be removed now
		//	and use single list on class
		List<BayesNetChangeI> suggestedAddList;
		List<BayesNetChangeI> suggestedDeleteList;
		List<BayesNetChangeI> suggestedReverseList;
		
/*		//Get copy of BANJO RNG, to see local RNG
		BanjoRandomNumber bRND = new BanjoRandomNumber();
		long seed = bRND.getBanjoSeed();
		
		//Seeding RNG with BANJO Seed passed in
		//	Utilized in VNS to grab random selection from list returned
		Random rnd = new Random(seed);*/
		
		//Stores all BayesNetChanges that occur on VNS Loop
		Stack<BayesNetChangeI> vnsNetChanges = new Stack<BayesNetChangeI>();
		
		//
		//		RANDOM NETWORK SELECTION METHODS
		//
		protected boolean getAddNetwork() throws Exception {
			//Randomly selects valid Add Network
			//	returns true if found
			suggestedAddList = proposerAdd.suggestBayesNetChanges( bayesNetManager );
			return getValidNetwork(suggestedAddList);
		}
		protected boolean getReverseNetwork() throws Exception {
			//Randomly selects valid Reverse Network
			//	returns true if found
			suggestedReverseList = proposerReverse.suggestBayesNetChanges( bayesNetManager );
			return getValidNetwork(suggestedReverseList);
		}
		protected boolean getDeleteNetwork() throws Exception {
			//Randomly selects valid Delete Network
			//	returns true if found
			suggestedDeleteList = proposerDelete.suggestBayesNetChanges( bayesNetManager );
			return getValidNetwork(suggestedDeleteList);
		}
		
		//Used by above methods to randomly select a cahnge network
		//	out of list provided
		protected boolean getValidNetwork(List networks) throws Exception{
			
			boolean foundNetwork = false;
			boolean isValidChange = false;
						
			// Gets an iterator for list of proposed changes
			//Iterator changeListIterator = networks.iterator();

			BayesNetChangeI[] changes = new BayesNetChangeI[networks.size()];
			changes = (BayesNetChangeI[]) networks.toArray(changes);
			
			//Will randomly jump through list until valid (non-cyclic) change is found
			//	POSSIBLE PROBLEM:
			//		The max number of attempts is the size of list returned
			//		It is possible that during those attempts no valid changes are found
			//		That case is not handled
			int trys = 0;
			//while ( changeListIterator.hasNext() && !foundNetwork) {
			while ( (trys < changes.length) && !foundNetwork) {	
				//Increment list, being used as counter
				//suggestedBayesNetChange = (BayesNetChangeI) changeListIterator.next();
				//changeListIterator.next();
				
				//using RNG to get a random index
				trys++;
				int index = rnd.nextInt(changes.length);
				index = index <= 0 ? 0 : index;
				index = index >= changes.length ? changes.length - 1 : index;
				
				//Random change in change suggestion array
				BayesNetChangeI suggestedBayesNetChange = changes[index];//(BayesNetChangeI) networks.get(index);
				//suggestedBayesNetChange = (BayesNetChangeI) changes[index];//networks.get(index);
				
				//Verifies that change is READY --- Not sure how this would not be true
				if ( suggestedBayesNetChange.getChangeStatus() ==  BANJO.CHANGESTATUS_READY ) {
					    
					// Note that the change will be "applied" to the underlying 
					// BayesNet structure in the cycleChecker. If we encounter a
					// cycle, the cycleChecker will undo the change immediately.
					isValidChange = cycleChecker.isChangeValid( bayesNetManager, suggestedBayesNetChange );
				}
				else {
	
				    // Proposer was not able to come up with a valid change
				    isValidChange = false;
				}
				
				if ( isValidChange ) {

					// Since the (valid) change is already applied (to the 
					// bayesNetManager), we can compute the score without 
				    // add'l prep
					
					//Save VNS network score
					vnsNetworkScore = evaluator.updateNetworkScore(bayesNetManager, suggestedBayesNetChange);						    

					//Change to Bayesian Network Manager already applied from Cycle Checker
					//	Need to update Change Status
					suggestedBayesNetChange.setChangeStatus( BANJO.CHANGESTATUS_READY );
					
					//Save Network Score
					highScoreSetUpdater.updateHighScoreStructureData( vnsNetworkScore );
					
					//Save Change to Stack
					vnsNetChanges.push(suggestedBayesNetChange);
					
					//Valid Network found
					foundNetwork = true;

                }
				else{
					// We need to undo the current change, so we can check 
					// the next change
					//suggestedBayesNetChange.setChangeStatus( BANJO.CHANGESTATUS_APPLIED );
				    //bayesNetManager.undoChange( suggestedBayesNetChange );
				    //evaluator.adjustNodeScoresForUndo( suggestedBayesNetChange );
				}
				
			}

			if(!foundNetwork){
				System.out.println("NO VALID NETWORK FOUND.... UH OH!");
			}
			return foundNetwork;
		}
		
		//
		//		VNS SEARCH METHODS
		//
		
		//	Initializes Add, Reverse, Delete List and Sets Base Network Score
		protected void setupSearch() throws Exception{
			//Get List of Add, Reverse and Delete Options
			suggestedAddList = proposerAdd.suggestBayesNetChanges( bayesNetManager );
			suggestedDeleteList = proposerDelete.suggestBayesNetChanges( bayesNetManager );
			suggestedReverseList = proposerReverse.suggestBayesNetChanges( bayesNetManager );

		}
		
		//Restores Network back to default, so next VNS search can 
		//	Start from same network state
		public void restoreNetworkToDefault() throws Exception{
			
			out.append("    Restoring VNS Network\n");
			//Remove Changes Made from VNS Search
			while(!this.vnsNetChanges.empty()){
				//Get change stored in Stack
				BayesNetChangeI netChange = (BayesNetChangeI) vnsNetChanges.pop();
				//Update Status so it can be undone
				netChange.setChangeStatus( BANJO.CHANGESTATUS_APPLIED );
				
				try{
					//Undo Change
					bayesNetManager.undoChange( netChange );
					//Update Score
					evaluator.adjustNodeScoresForUndo( netChange );
				}
				catch(BanjoException e ){
					//Change was already applied, so no need to apply again
					out.append("    BayesNetChange Already Processed\n");
				}
				 
			}
		}
		public void adoptCurrentNetworkChanges() throws Exception{
			
			//Adopt Changes from VND Search
			vndExecuter.adoptCurrentNetworkChanges();
			
			//Adopt Changes from VNS Search
			vnsNetChanges.clear();
			
			//VNS Network, is now Global Best Network
			//globalMaxNetworkScore = localMaxNetworkScore;
				//Above line should be correct, but this is just in case
			globalMaxNetworkScore = localMaxNetworkScore > globalMaxNetworkScore ? localMaxNetworkScore : globalMaxNetworkScore;
			
		}
		
		//	VNS Search Sections
		protected void executeAdd() throws Exception{

			//Get Random ADD Network for current BayesNetwork
			getAddNetwork();
			out.append("   ################################\n");
			out.append("   # VNS ADD Search:   # VNS Score:" + vnsNetworkScore + "\n");
			out.append("   ################################\n");
			
			//Execute VND Local Search
			vndExecuter.executeSearch();
			//System.out.println("        VNS ADD - Global: " + globalMaxNetworkScore);
		}
		protected void executeDelete() throws Exception{
			//Get Random Delete Network for Current Network
			getDeleteNetwork();
			out.append("   ##############################\n");
			out.append("   # VNS DEL Search:  #   VNS Max" + vnsNetworkScore + "\n");
			out.append("   ##############################\n");
			
			//Execute VND Local Search
			vndExecuter.executeSearch();
			//System.out.println("        VNS DEL - Global: " + globalMaxNetworkScore);
		}
		protected void executeReverse() throws Exception{
			//Get Random Reverse Network for Current Network
			getReverseNetwork();
			out.append("   #############################\n");
			out.append("   # VNS REV Search:   # VNS Max" + vnsNetworkScore + "\n");
			out.append("   #############################\n");
			
			//Execute VND Local Search
			vndExecuter.executeSearch();
			//System.out.println("        VNS REV - Global: " + globalMaxNetworkScore);
		}
		
		// VNS Main Search
		public void executeSearch() throws Exception {
			boolean searching = true;
			
			//Used at end to save final network state for reporting
			EdgesWithCachedStatisticsI parents;
			System.out.println("\n\n#######################################\n\n");
			System.out.println("Starting VNS Search");
			while( searching && searchTerminator.checkTerminationCondition() ){
				//System.out.println("    VNS New Search - Global: " + globalMaxNetworkScore);
				//Adopt Current Network as Default
				adoptCurrentNetworkChanges();
				//out.append("Staring New VNS Search: Global Max: " + globalMaxNetworkScore + "\n");
				
				//Get Add, Reverse, Delete List and  save Base Network Score
				setupSearch();
				
				//Select Random Add Network from List
				// Perform VND Local Search
				//executeAdd();
				executeDelete();
				
				//out.append("    VNS Network Score:  " + vnsNetworkScore + "\n");
				//out.append("    VND Returned Score: " + vndNetworkScore + "\n");
				//out.append("    Local Max Network : " + localMaxNetworkScore + "\n");
				//out.append("    Global Max Network: " + globalMaxNetworkScore + "\n");
				//out.append("    Global - Local: " + (globalMaxNetworkScore - localMaxNetworkScore) + "\n");
				
				//If better network was NOT found, continue.
				if(globalMaxNetworkScore >= localMaxNetworkScore){
					
					//Clear Network Back to Original Search Start State
					restoreNetworkToDefault();
					
					//Select Random Reverse Network from List
					//	Perform VND Local Search
					//executeReverse();
					executeReverse();
					
					//out.append("    VNS Network Score:  " + vnsNetworkScore + "\n");
					//out.append("    VND Returned Score: " + localMaxNetworkScore + "\n");
					//out.append("    Local Max Network : " + localMaxNetworkScore + "\n");
					//out.append("    Global Max Network: " + globalMaxNetworkScore + "\n");
					//out.append("    Global - Local: " + (globalMaxNetworkScore - localMaxNetworkScore) + "\n");
					
					//If better network was NOT found, continue.
					if(globalMaxNetworkScore >= localMaxNetworkScore){
						
						//Clear Network Back to Original Search State
						restoreNetworkToDefault();
						
						//Select Random Delete Network
						//	Perform VND Local Search
						//executeDelete();
						executeAdd();
						
						//out.append("    VNS Network Score:  " + vnsNetworkScore + "\n");
						//out.append("    VND Returned Score: " + localMaxNetworkScore + "\n");
						//out.append("    Local Max Network : " + localMaxNetworkScore + "\n");
						//out.append("    Global Max Network: " + globalMaxNetworkScore + "\n");
						//out.append("    Global - Local: " + (globalMaxNetworkScore - localMaxNetworkScore) + "\n");
						
						//If better network was NOT found, continue.
						if(globalMaxNetworkScore >= localMaxNetworkScore){
							
							//Clear Network Back to Original Search State
							restoreNetworkToDefault();
							
							if(restartCount < maxRestarts){
								System.out.println("VNS Restart: " + restartCount + " of " + maxRestarts +" Global: " + globalMaxNetworkScore);
								restartCount++;
							}
							else{
								//Cancel search
								searching = false;
								out.append("VNS Complete\n");
								out.append("High Score: " + globalMaxNetworkScore + "\n");
								
								System.out.println("    VNS Complete - Global: " + globalMaxNetworkScore);
								System.out.println("\n\n#######################################\n\n");
							}
							
						}
					}
				}
				
				//System.out.println("  VNS Better Solution Found - Global: " + globalMaxNetworkScore);
			}
			
			//Get Network for final reporting
			parents = bayesNetManager.getCurrentParents();
			
			//Create network tree
			highScoreStructureSinceRestart = new BayesNetStructure( 
	                parents,
	                globalMaxNetworkScore, 0 );
			
			//Add network to reporting structure
			highScoreStructureSet.add( new BayesNetStructure( 
	                highScoreStructureSinceRestart,
	                globalMaxNetworkScore, 
	                networksVisitedGlobalCounter ));
			
			//Output VNS/VND Search Steps to report
			searcherStatistics.recordSpecifiedData(out);
			
			//Output BANJO Report
			finalCleanup();
			
			//Output Final Network to Report
			postProcessor.execute();
			
			out.append("High Score: " + globalMaxNetworkScore + "\n");
		}

		//Part of default Greedy Search, not used
		protected int sizeOfListOfChanges() {
		    
		    // bound on number of ("single step") local moves
		    return BANJO.CHANGETYPE_COUNT * varCount * varCount;
		}
	}
	
	
	//Used to perform the ADD/REV/DEL searches in VND
	protected class NeighborhoodSearch{
		Random rnd = new Random(seed);
		//Prospers for the various VND searches
		public void suggestAddNetworks() throws Exception{
			// Get the list of changes from the proposer
			suggestedChangeList = proposerAdd.suggestBayesNetChanges( bayesNetManager );
		}
		public void suggestDeleteNetworks() throws Exception{
			// Get the list of changes from the proposer
			suggestedChangeList = proposerDelete.suggestBayesNetChanges( bayesNetManager );
		}
		public void suggestReverseNetworks() throws Exception{
			// Get the list of changes from the proposer
			suggestedChangeList = proposerReverse.suggestBayesNetChanges( bayesNetManager );
		}
		
		//Loops through all networks in suggestedChangeList
		// to find best change, each proposer updates same list
		// so only one method is needed to for each search type
		protected boolean findBestNetwork() throws Exception{
			
			boolean changedNetwork = false;
			boolean isValidChange;
			//int validChangesFound = 0;
			double score;
			suggestedChange.changeID = -1;
			suggestedChange.networkScore = -Double.MAX_VALUE;
			
			//Score returned from tested network
			//double bayesNetScore = currentNetworkScore;
			//Initialize VND to Local Max
			vndNetworkScore = localMaxNetworkScore;
			//System.out.println("Local Max: " + localMaxNetworkScore);
			// Process each BayesNetChange in the list
			BayesNetChangeI[] changes = new BayesNetChangeI[suggestedChangeList.size()];
			changes = (BayesNetChangeI[]) suggestedChangeList.toArray(changes);
			
			//Iterator changeListIterator = suggestedChangeList.iterator();
			
			//out.append("\t\t"+ suggestedChangeList.size() + " Suggested Networks Found\n");
			
			//Will loop through change list looking for highest possible change
			int possibleChanges = changes.length;

			for(int i = 0; i < possibleChanges; i++){
				networksVisitedGlobalCounter++;
				networksVisitedSinceHighScore++;
				networksVisitedSinceRestart++;

				//Get suggested change
				BayesNetChangeI suggestedBayesNetChange = changes[i];
				if ( suggestedBayesNetChange.getChangeStatus() ==  BANJO.CHANGESTATUS_READY ) {
					    
						// Note that the change will be "applied" to the underlying 
						// BayesNet structure in the cycleChecker. If we encounter a
						// cycle, the cycleChecker will undo the change immediately.
						isValidChange = cycleChecker.isChangeValid( bayesNetManager, suggestedBayesNetChange );
				}
				else {
	
				    // Proposer was not able to come up with a valid change
				    isValidChange = false;
				}
				
				if ( isValidChange ) {
					
					//validChangesFound++;
					
					// Since the (valid) change is already applied (to the 
					// bayesNetManager), we can compute the score without 
				    // add'l prep
					score = evaluator.updateNetworkScore(bayesNetManager, suggestedBayesNetChange);
					
					if(score > suggestedChange.networkScore){
						suggestedChange.networkScore = score;
						suggestedChange.changeID = i;
						//System.out.println("Change ID: " + suggestedChange.changeID + ", Score: " + suggestedChange.networkScore);
					}

					//Update Status so it can be undone
					suggestedBayesNetChange.setChangeStatus( BANJO.CHANGESTATUS_APPLIED );
					
					//Undo change to return network back to original state
				    bayesNetManager.undoChange( suggestedBayesNetChange );
				    evaluator.adjustNodeScoresForUndo( suggestedBayesNetChange );

				}
			}
			
			if(suggestedChange.networkScore > localMaxNetworkScore){ //found a better network
				
				//Save new score to localMax
				localMaxNetworkScore = suggestedChange.networkScore;
				vndNetworkScore = suggestedChange.networkScore;
				

				//Apply change to network
				BayesNetChangeI bestNetworkChange = changes[suggestedChange.changeID];
				cycleChecker.isChangeValid( bayesNetManager, bestNetworkChange );
				
				//Update Network score on network
				evaluator.updateNetworkScore(bayesNetManager, bestNetworkChange);
					
				//Change already applied to Bayesian Network Manager
				//	through Cycle Checker, so just update change status
				bestNetworkChange.setChangeStatus( BANJO.CHANGESTATUS_READY );
				
				//Update high score
				highScoreSetUpdater.updateHighScoreStructureData( localMaxNetworkScore );
				
				//Add Change to Stack
				vndExecuter.vndNetChanges.push( bestNetworkChange);
				
				changedNetwork = true;
				
			}
			
			searchTerminator.checkTerminationCondition();
			

			return changedNetwork;
		}
		
		//Performs the VND ADD Sub Search
		public boolean executeAdd() throws Exception {
			boolean networkChanged = false;
			//Get Possible ADD Network Changes
			suggestAddNetworks();
			
			//Find Best Change, if exist
			networkChanged = findBestNetwork();
			//out.append("\tExecuted VND: Subsearch ADD, Score: " + vndNetworkScore + "\n");
			//out.append("\t\tNetwork Changed: " + networkChanged + ", Local Max: " + localMaxNetworkScore + "\n");
			//System.out.println("                VND ADD - Local Max" + localMaxNetworkScore);
			return networkChanged;
			
		}
		//Performs VND Delete Sub Search
		public boolean executeDelete() throws Exception {
			boolean networkChanged = false;
			suggestDeleteNetworks();
			networkChanged = findBestNetwork();
			//out.append("\tExecuted VND: Subsearch DEL, Score: " + vndNetworkScore + "\n");
			//out.append("\t\tNetwork Changed: " + networkChanged + ", Local Max: " + localMaxNetworkScore + "\n");
			//System.out.println("                VND DEL - Local Max" + localMaxNetworkScore);
			return networkChanged;
			
		}
		//Performs VND REV Sub Search
		public boolean executeReverse() throws Exception {
			boolean networkChanged = false;
			suggestReverseNetworks();
			networkChanged = findBestNetwork();
			//out.append("\tExecuted VND: Subsearch REV, Score:" + vndNetworkScore + "\n");
			//out.append("\t\tNetwork Changed: " + networkChanged + ", Local Max: " + localMaxNetworkScore + "\n");
			//System.out.println("                VND REV - Local Max" + localMaxNetworkScore);
			return networkChanged;			
		}
	}

	//Slightly modified Greedy Updated
	protected class SingleHighScoreUpdater extends HighScoreSetUpdater {
	    
	    // Note that for the single best network case, we don't check for
	    // equivalence classes between networks
		protected synchronized void updateHighScoreStructureData( double bayesNetScore ) 
				throws Exception {
			
			// If we actually have a new high score, "record" the related counter
			if ( bayesNetScore > currentBestScoreSinceRestart ) {
			    
				networksVisitedSinceHighScore = 0;
				
				// Even though the naming may seem off, we need to update the 
				// "highScoreStructureSinceRestart"
				currentBestScoreSinceRestart = bayesNetScore;
				highScoreStructureSinceRestart.assignBayesNetStructure(
						bayesNetManager.getCurrentParents(), 
						currentBestScoreSinceRestart,
						networksVisitedGlobalCounter );		
			}			
			
			if ( bayesNetScore > nBestThresholdScore ) {
		     
				if ( bayesNetScore > nBestThresholdScore ) {
					
					// Remove the current high-scoring network then 
					// add the new network to the nBest set
					highScoreStructureSet.remove( highScoreStructureSet.last());
					highScoreStructureSet.add( new BayesNetStructure( 
							highScoreStructureSinceRestart,
							bayesNetScore, 
							networksVisitedGlobalCounter ));
					
					// Finally, we need to update the new threshold score:
					nBestThresholdScore = bayesNetScore;
				}
			}
		}
	}
	
	/**
	 * 		TERMINATION TESTER SECTION
	 * 			Slightly modified to work with VNS
	 *
	 */
	protected class TerminationTester extends SearchMultipleTerminator {
	    
	    protected boolean checkTerminationCondition() throws Exception {	        

	        //checkForRestart();
	    	elapsedTime = getElapsedTime();

	        if (  elapsedTime >= nextFeedbackTime ) {
	        	System.out.println("Terminator: VNS Restart: " + restartCount + " of " + maxRestarts +" Global: " + globalMaxNetworkScore);
	        	nextFeedbackTime += feedbackTimeDelta;
	        }
	        
	        return ( terminator[0].checkTerminationCondition() && 
	                 terminator[1].checkTerminationCondition() );//&& 
	                 //terminator[2].checkTerminationCondition() );
	    }
	}

	/**
     * Inner class for terminating a search based on a limit on the search time.
     */
	protected class TimeTerminator extends SearchTerminator {
		
	    private boolean timeRemaining = true;
		
		protected boolean checkTerminationCondition() throws Exception {
		    								
			elapsedTime = System.currentTimeMillis() - startTime;

			// Check if we reached the time or max search loop limit
			if ( maxSearchTime >= 0 && maxSearchTime <= elapsedTime ) {
			    
			    timeRemaining = false;
			}

			return ( timeRemaining );
		}
	}

    /**
     * Inner class for terminating a search based on a limit on the number of iterations.
     */
	protected class IterationsTerminator extends SearchTerminator {
		
		private boolean searchLoopsRemaining = true;
		
		protected boolean checkTerminationCondition() throws Exception {
		    
			if ( ( maxSearchLoops >= 0 && maxSearchLoops <= networksVisitedGlobalCounter)
				|| maxNetworksVisitedInInnerLoop == 0 ) {
			    
			    searchLoopsRemaining = false;
			}

			return ( searchLoopsRemaining );
		}
	}

    /**
     * Inner class for terminating a search based on a limit on the number of restarts.
     */
	protected class RestartsTerminator extends SearchTerminator {
	    
		protected boolean checkTerminationCondition() throws Exception {

		    // hjs 8/23/05 Change the logic slightly to enable test runs with 0 restarts
	        if ( ( networksVisitedSinceRestart >= minNetworksVisitedBeforeRestart &&
					networksVisitedSinceHighScore >= minNetworksVisitedSinceHighScore )
					|| networksVisitedSinceRestart >= maxNetworksVisitedBeforeRestart
					) {
    			
				if ( restartCount >= maxRestarts ) {

					elapsedTime = System.currentTimeMillis() - startTime;
			        return false;
			    }
				restartCount++;
			
			  	// First: restart the network
			    bayesNetManager.initializeBayesNet();
				restartsAtCounts.append( ", " + networksVisitedGlobalCounter );
				
				// Second: reset the score
				currentBestScoreSinceRestart = 
				    evaluator.computeInitialNetworkScore(bayesNetManager);
				
				// Third: reset the current high score (the working copy; 
				//		the global copy is tracked in the high-score-set)
				highScoreStructureSinceRestart = new BayesNetStructure( 
                        bayesNetManager.getCurrentParents(),
						currentBestScoreSinceRestart, 
						networksVisitedGlobalCounter );
				
				// Fourth: Reset the decider
				decider.setCurrentScore( currentBestScoreSinceRestart );
				
				// Finally: reset the counter
				networksVisitedSinceRestart = 0;
			}
	        else {
	            
	            if ( restartCount >= maxRestarts ) {

					elapsedTime = System.currentTimeMillis() - startTime;
			        return false;
			    }
	        }
	        
			return true;
		}
	}
	
	protected void checkForRestart() throws Exception {
		
		// Check if finished
		
		// ------------------
		// Restart the search:
		// ------------------
		// Logic for restarting: "Always visit at least minNetworksVisitedBeforeRestart
		// networks after a restart, AND at least minNetworksVisitedSinceHighScore after
		// the last highScore was found"  OR
		// "Visit at most maxNetworksVisitedBeforeRestart after a restart"
		if ( ( networksVisitedSinceRestart >= minNetworksVisitedBeforeRestart &&
				networksVisitedSinceHighScore >= minNetworksVisitedSinceHighScore ) ||
				networksVisitedSinceRestart >= maxNetworksVisitedBeforeRestart ) {
		
		  	// - First restart the network
		    bayesNetManager.initializeBayesNet();
		    			
			restartCount++;
			restartsAtCounts.append( ", " + networksVisitedGlobalCounter );
			
			//- Second the score
			currentBestScoreSinceRestart = 
			    evaluator.computeInitialNetworkScore(bayesNetManager);
			
			//- Third the current high score (the working copy; the global copy is
			//   		tracked in the high-score-set)
			highScoreStructureSinceRestart = new BayesNetStructure( 
			        bayesNetManager.getCurrentParents(), 
					currentBestScoreSinceRestart, 
					networksVisitedGlobalCounter );
			
			//- Fourth: Reset the decider
			decider.setCurrentScore( currentBestScoreSinceRestart );
			
			//- Finally, Reset the counter
			networksVisitedSinceRestart = 0;
		}
	}
	
	/**
	 * 	CLASS LEVEL SETUP AND EXECUTION METHODS
	 *
	 */
	public void updateProcessData(Settings processData) throws Exception {
		// TODO Auto-generated method stub

	}
	
	public void setupSearch() throws Exception{
		StringBuffer outputBuffer = new StringBuffer( );
		outputBuffer.append("\n\t ##############################\n\t #####  VNS Search Setup  ####\n");
			
		//
		//	Setup Searcher
		//
		outputBuffer.append("\t ##### 1.   Load VNS and VND Executers\n");
			//Search Executers
		vnsExecuter = new VNSExecuter();
		vndExecuter = new VNDExecuter();
		outputBuffer.append("\t ##### 2.   Load Subsearch Executers\n");			
			//Sub Search Executers - used in both VNS and VND
		subsearch = new NeighborhoodSearch();

		
		//-- searchExecuter = new VNSExecuter();
	
		//
		//		Setup Proposer
		//
		outputBuffer.append("\t ##### 3.   Load Proposers\n");
		proposerAdd = new ProposerAdd(bayesNetManager, processData);
		proposerDelete = new ProposerDelete(bayesNetManager, processData);
		proposerReverse = new ProposerReverse(bayesNetManager, processData);
		
		//Default Proposer
		//proposer = proposerAdd;
		
		//Setup Evaluator -- Default from Greedy
		outputBuffer.append("\t ##### 4.   Load Greedy Evaluator\n");
		evaluator = new EvaluatorBDe( bayesNetManager, processData );
		
		//Setup Cycle Checker -- Default from Greedy
		outputBuffer.append("\t ##### 5.   Load Greddy Cycle Checker\n");
		cycleChecker = new CycleCheckerCheckThenApply( bayesNetManager, processData );
		
		//Setup Decider  -- Default from Greedy
		outputBuffer.append("\t ##### 6.   Load Greedy Decider\n");
		decider = new DeciderGreedy( bayesNetManager, processData, currentBestScoreSinceRestart );
		
		//Setup Searcher Stats
		outputBuffer.append("\t ##### 7.   Create Search Stats Buffer\n");
		searcherStats = new StringBuffer( BANJO.BUFFERLENGTH_STAT_INTERNAL );
        
		outputBuffer.append("\t ##### 8.   Append to Search Stats Buffer\n");
        searcherStats.append( StringUtil.formatRightLeftJustified( 
                newLinePlusPrefix, 
                BANJO.SETTING_SEARCHERCHOICE_DISP,
                StringUtil.getClassName(this), null, lineLength ) );
        
        networksVisitedSinceHighScore = 0;
        networksVisitedSinceRestart = 0;
        
        //
        //		SETUP BAYES NET MANAGERS
        //
        	//Only need one manager, but will need to keep local copies of best network
        	// In subsearch areas and VNS overall search
        	// processData contains initial network file
        outputBuffer.append("\t ##### 9.   Create Bayesian Network Manager\n");
    	bayesNetManager = new BayesNetManager(processData);
//########
//  Set up initial network structure
    	
//    	EdgesWithCachedStatisticsI parents = bayesNetManager.getCurrentParents();
//
    		//Get Initial Score of Network
    	outputBuffer.append("\t ##### 10.  Compute Initial Network Score\n");

		//Initialize all scores to current network
    	globalMaxNetworkScore = evaluator.computeInitialNetworkScore(bayesNetManager);
    	localMaxNetworkScore = globalMaxNetworkScore;
    	vnsNetworkScore = globalMaxNetworkScore;
    	vndNetworkScore = globalMaxNetworkScore;
    	
    	outputBuffer.append("\t ##### 11.  Create Terminators\n");
    	terminator[ 0 ] = new TimeTerminator();
    	terminator[ 1 ] = new IterationsTerminator();
    	terminator[ 2 ] = new RestartsTerminator();
    	searchTerminator = new TerminationTester();
    	
    	//
    	//		HIGH SCORE UPDATE
    	//		Will revisit this, just used for stats tracking purposes
    	outputBuffer.append("\t ##### 12.  Create High Score Updater\n");
    	highScoreSetUpdater = new SingleHighScoreUpdater();
		// Set up a post-processor object, so we can display its options, if necessary
    	
    	 // Set the initial network as the current best score:
        highScoreStructureSinceRestart = new BayesNetStructure( 
                bayesNetManager.getCurrentParents(),
                globalMaxNetworkScore, 0 );
                
        highScoreStructureSet = new TreeSet();
        
        // Add the initial network (trivially) to the set of n-best networks
        highScoreStructureSet.add( new BayesNetStructure( 
                highScoreStructureSinceRestart,
                globalMaxNetworkScore, 
                networksVisitedGlobalCounter ));
        outputBuffer.append("\t ##### 13.  Create Post Processor\n");       
    	postProcessor = new PostProcessor( processData );

    	maxRestarts = Long.parseLong( 
                processData.getValidatedProcessParameter(
                BANJO.SETTING_MAXRESTARTS ) );

        
    	
    	outputBuffer.append("\t ##############################\n\n\n");
    	
    	//tmpStatisticsBuffer.append( BANJO.FEEDBACK_NEWLINE );
        //statisticsBuffer.append( tmpStatisticsBuffer );
    	//commitData( outputResultsOnly , outputBuffer );
    	searcherStatistics.recordSpecifiedData(outputBuffer);
    	
	}
	
	private boolean validateRequiredData() throws Exception{
		boolean isDataValid = true;
		
		return isDataValid;
	}
	
	public void executeSearch() throws Exception {
		
        try {
            
    		// Feedback of selected settings to the user, with option to stop the search
    		if ( !askToVerifySettings() ) {
    		    
    		    return;
    		}
    		
    		// Compute the time it took for preparation of the search
    		elapsedTime = System.currentTimeMillis() - startTime;
    		processData.setDynamicProcessParameter ( 
    		        BANJO.DATA_TOTALTIMEFORPREP, Long.toString( elapsedTime ) );
    		
    		// Record the initial data
    		searcherStatistics.recordInitialData(this);
    
    		searcherStats = new StringBuffer( BANJO.BUFFERLENGTH_STAT_INTERNAL );
    
    		// (Re)start the timer for the actual search 
    		startTime = System.currentTimeMillis();
    
    		// Execute the actual search code (which varies between global and local
    		// search strategies)
    		vnsExecuter.executeSearch();
    		
    		// We are now ready to do the postprocessing, but since we don't throw
    		// exceptions directly anymore, we need to run a quick check:
//    		if ( processData.wereThereProblems() ) {
//    		    
//    		    throw new BanjoException( BANJO.ERROR_CHECKPOINTTRIGGER,
//    		            "(Checkpoint) " +
//    		            "Banjo could not prepare the post-processing successfully; " +
//    		            "the following issues prevented further program execution:" + 
//    		            BANJO.FEEDBACK_NEWLINE +
//    		            processData.compileErrorMessages().toString() );
//    		}
    		
            // Execute the postprocessing functions
            //postProcessor.execute();

        }
        catch ( BanjoException e ) {
        
            if ( e.getExceptionType() == BANJO.ERROR_BANJO_OUTOFMEMORY ) {
                
                handleOutOfMemory();
                throw new BanjoException( e );
            }
            else {

                throw new BanjoException( e );
            }
        }
        catch ( OutOfMemoryError e ) {
            
            handleOutOfMemory();
            
            throw new BanjoException( BANJO.ERROR_BANJO_OUTOFMEMORY,
                    "Out of memory in (" +
                    StringUtil.getClassName(this) +
                    ".executeSearch)" );
        }
	}

}
