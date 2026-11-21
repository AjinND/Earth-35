package world.earth35.world;

import java.util.*;
import java.util.regex.*;
import world.earth35.world.State.*;

/** Names in prose identify intended companions; only physical state establishes their presence. */
public final class ActivityGrounding {
    private ActivityGrounding() {}
    private static final Pattern WITH=Pattern.compile("\\b(?:with|alongside|joined by)\\s+",Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT=Pattern.compile("\\s*(?:,\\s*(?:and\\s+)?|and\\s+|&\\s*)",Pattern.CASE_INSENSITIVE);
    private static final Pattern SOLO_REFLECTION=Pattern.compile("^(?:i (?:will|want to|plan to) )?(?:spend(?:ing)? (?:\\d+ minutes?|some time|time) )?(?:reflect(?:ing)? (?:on|about)|think(?:ing)? (?:about|of|back to|back on))\\s+",Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERRED_EXCHANGE=Pattern.compile("\\b(?:conversation|discussion|exchange|chat|meeting)\\s*$",Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_TIME=Pattern.compile("\\b(?:yesterday|earlier|previous(?:ly)?|past|tomorrow|later|future|(?:last|next)\\s+(?:time|day|week|month|morning|evening|meeting))\\b",Pattern.CASE_INSENSITIVE);

    public static boolean present(State s,Resident actor,Resident other) {
        return actor!=null&&other!=null&&!Objects.equals(actor.id,other.id)
            &&Double.isFinite(actor.x)&&Double.isFinite(actor.z)&&Double.isFinite(other.x)&&Double.isFinite(other.z)
            &&Math.hypot(actor.x-other.x,actor.z-other.z)<=10&&Social.perceivable(s,actor,other);
    }

    /** A solo reflection can refer to a past exchange or future meeting without enrolling its named people now. */
    public static List<Resident> activityCompanions(State s,Resident actor,String label) {
        if(label!=null) {
            String text=label.strip();Matcher reflection=SOLO_REFLECTION.matcher(text),with=WITH.matcher(text);
            if(reflection.find()&&with.find()&&with.group().strip().equalsIgnoreCase("with")
                &&REFERRED_EXCHANGE.matcher(text.substring(reflection.end(),with.start())).find()
                &&OTHER_TIME.matcher(text).find()&&!with.find())return List.of();
        }
        return companions(s,actor,label);
    }

    /** Read a coordinated name list, stopping before topic words such as "about" or "to assess". */
    public static List<Resident> companions(State s,Resident actor,String message) {
        if(message==null||message.isBlank())return List.of();
        Map<String,List<Resident>> names=new LinkedHashMap<>();
        for(Resident resident:s.agents.values()) {
            alias(names,resident.id,resident);alias(names,resident.name,resident);
            if(resident.name!=null&&!resident.name.isBlank())alias(names,resident.name.strip().split("\\s+")[0],resident);
        }
        List<String> aliases=names.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        String text=message.toLowerCase(Locale.ROOT);Map<String,Resident> result=new LinkedHashMap<>();
        Matcher with=WITH.matcher(text);
        while(with.find()) {
            int index=with.end();
            while(index<text.length()) {
                String matched=null;
                for(String name:aliases)if(text.startsWith(name,index)&&nameBoundary(text,index+name.length())) {
                    matched=name;break;
                }
                if(matched==null)break;
                Engine.require(names.get(matched).size()==1,"Companion name '"+matched+"' is ambiguous; use their full name or resident ID");
                Resident resident=names.get(matched).getFirst();
                if(actor==null||!Objects.equals(actor.id,resident.id))result.put(resident.id,resident);
                index+=matched.length();Matcher next=NEXT.matcher(text);next.region(index,text.length());
                if(!next.lookingAt())break;index=next.end();
            }
        }
        return List.copyOf(result.values());
    }
    private static boolean nameBoundary(String text,int index) {
        if(index==text.length())return true;
        if(Character.isLetterOrDigit(text.charAt(index)))return false;
        return !text.startsWith("'s",index)&&!text.startsWith("’s",index);
    }
    private static void alias(Map<String,List<Resident>> names,String value,Resident resident) {
        if(value==null||value.isBlank())return;
        List<Resident> matches=names.computeIfAbsent(value.strip().toLowerCase(Locale.ROOT),ignored->new ArrayList<>());
        if(!matches.contains(resident))matches.add(resident);
    }
}
