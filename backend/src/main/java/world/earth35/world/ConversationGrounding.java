package world.earth35.world;

import java.util.*;
import java.util.regex.*;
import world.earth35.world.State.*;

/** Reject explicit current-company claims against physical state, without rewriting residents' speech. */
public final class ConversationGrounding {
    private ConversationGrounding() {}
    private static final Pattern CURRENT_COMPANY=Pattern.compile(
        "(?i)\\b(?:i\\s+am|i['’]m|we\\s+are|we['’]re)\\s+(?:(?:currently|still|already|right now)\\s+)*"
        +"(?:here|at|talking|chatting|discussing|working|walking|gardening|checking|assessing|inspecting|sitting|standing|eating|drinking)\\b");
    private static final Pattern PRESENT_SUBJECT=Pattern.compile("(?i)\\b(?:is|are)\\s+(?:(?:currently|still|already|both)\\s+)*(?:here|with\\s+(?:me|us))\\b");
    private static final Pattern WITH=Pattern.compile("(?i)\\b(?:with|joined by)\\b");
    private static final Pattern REPORTED=Pattern.compile("(?i)\\b(?:said|told|wrote|quoted|recalled|remembered|asked)\\b");
    private static final Pattern PROSPECTIVE=Pattern.compile("(?i)\\b(?:hope|hoping|plan|planning|want|wanting|intend|intending|wish|wishing|about|whether|if|might|would|could|tomorrow|later|after|before|earlier|yesterday|previously)\\b");
    private static final Pattern FUTURE_TIME=Pattern.compile("(?i)\\b(?:tomorrow|later|next\\s+(?:week|day|morning|evening))\\b");
    private static final Pattern WORD_START=Pattern.compile("(?<![\\p{L}\\p{N}])(?=\\p{L})");
    private static final Pattern UNCERTAIN_BELIEF=Pattern.compile("(?i)\\b(?:i|we)\\s+(?:do\\s+not\\s+think|don['’]t\\s+think|doubt)\\b"
        +"|\\b(?:i\\s+am|i['’]m|we\\s+are|we['’]re)\\s+not\\s+sure\\b");

    public static void validate(State s,Resident speaker,String message) {
        if(message==null||message.isBlank())return;
        // Check one sentence at a time, so a present location does not turn a later invitation
        // or a past conversation into a current attendance assertion.
        for(String clause:message.split("(?<=[.!?;\\n])")) {
            if(clause.strip().endsWith("?")||FUTURE_TIME.matcher(clause).find())continue;
            Set<Resident> claimed=new LinkedHashSet<>();
            Matcher current=CURRENT_COMPANY.matcher(clause);
            while(current.find()) {
                if(unasserted(clause,current.start()))continue;
                Matcher with=WITH.matcher(clause);with.region(current.end(),clause.length());
                if(with.find()&&!PROSPECTIVE.matcher(clause.substring(current.end(),with.start())).find())
                    claimed.addAll(ActivityGrounding.companions(s,speaker,clause.substring(with.start())));
            }
            Matcher subject=PRESENT_SUBJECT.matcher(clause);
            while(subject.find()) {
                if(unasserted(clause,subject.start()))continue;
                String names=clause.substring(0,subject.start());
                if(!PROSPECTIVE.matcher(names).find()) {
                    // Allow introductory words and comma-separated name lists before the assertion.
                    Matcher start=WORD_START.matcher(names);
                    while(start.find())claimed.addAll(ActivityGrounding.companions(s,speaker,"with "+names.substring(start.start())));
                    claimed.addAll(ActivityGrounding.companions(s,speaker,clause.substring(subject.start())));
                }
            }
            for(Resident other:claimed)Engine.require(ActivityGrounding.present(s,speaker,other),
                other.name+" is not physically present within talking distance. Do not claim they are here with you; describe the actual invitation, plan or past exchange instead");
        }
    }
    private static boolean unasserted(String clause,int claimStart) {
        String prefix=clause.substring(0,claimStart);
        if(REPORTED.matcher(prefix).find()||prefix.indexOf('"')>=0||prefix.indexOf('“')>=0)return true;
        // An uncertain belief is not an attendance assertion. Keep its scope local so
        // "I doubt it, but Aisha is here" still validates the affirmative clause.
        String[] segments=prefix.split("(?i)[,:]|\\b(?:but|however|yet)\\b",-1);
        return UNCERTAIN_BELIEF.matcher(segments[segments.length-1]).find();
    }
}
