package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Persistent, private perception and deliberation. Interpretations never become world facts. */
public final class Cognition {
    private Cognition() {}
    public static final int NOTICE_LIMIT=12,OUTCOME_LIMIT=12,REFLECTION_LIMIT=8,VISIT_LIMIT=64;
    public static class Mind {
        public DailyLife.History dailyLife=new DailyLife.History();
        public long revision,reviewedRevision,lastDeliberatedMinute=-1,observedMinute=-1;
        public Map<String,String> perceived=new LinkedHashMap<>();
        public Scene scene;
        public List<Notice> notices=new ArrayList<>();
        public Map<String,Visit> visits=new LinkedHashMap<>();
        public Intention intention;
        public String feeling;
        public long feelingMinute;
        public List<Reflection> reflections=new ArrayList<>();
        public List<Outcome> outcomes=new ArrayList<>();
    }
    public record Scene(String location,String place,String purpose,boolean indoors,String weather,int temperature,
                        String daylight,List<String> objects,List<String> visiblePlaces) {}
    public record Notice(long revision,String kind,String description,int priority,long minute) {}
    public record Visit(String location,String name,int count,long lastMinute) {}
    public record Intention(String focus,String nextStep,String reason,String target,long chosenMinute,long reviewMinute) {}
    public record Reflection(String lesson,String outcomeId,long minute) {}
    public record Outcome(String id,String action,String target,String status,String result,long minute) {}
    public record IntentionDraft(String focus,String nextStep,String reason,String target,Integer reviewAfterMinutes) {}
    public record Update(IntentionDraft intention,boolean clearIntention,String feeling,String lesson,String outcomeId,Relationships.Draft relationship) {
        public Update(IntentionDraft intention,boolean clearIntention,String feeling,String lesson,String outcomeId) {
            this(intention,clearIntention,feeling,lesson,outcomeId,null);
        }
    }
    public record View(Scene surroundings,List<Notice> noticedChanges,Intention intention,boolean intentionReviewDue,
                       String feeling,long feelingMinute,List<Reflection> reflections,List<Outcome> recentOutcomes,
                       List<Visit> visitedPlaces,boolean reconsider) {}

    public static Mind mind(Resident a) {if(a.mind==null)a.mind=new Mind();return a.mind;}
    private static void notice(Mind m,String kind,String text,int priority,long minute) {
        m.notices.add(new Notice(++m.revision,kind,text,priority,minute));
        while(m.notices.size()>NOTICE_LIMIT)m.notices.removeFirst();
    }
    /** Called on observation, never a model call. Repeated reads do not consume attention. */
    public static void perceive(State s,Resident a,Map<String,Boolean> availability) {
        Mind m=mind(a);Building place=s.buildings.get(a.location);boolean indoors=!Social.outdoors(s,a);
        int time=(int)Math.floorMod(s.minute,1440);
        String daylight=time<360||time>=1200?"night":time<480?"dawn":time>=1080?"dusk":"daylight";
        List<String> visiblePlaces=indoors?List.of():s.buildings.values().stream()
            .filter(b->!b.id.equals(a.location)&&Math.hypot(b.x-a.x,b.z-a.z)<=30)
            .filter(b->{Resident marker=new Resident();marker.id="place:"+b.id;marker.location="road";
                marker.x=Places.entrance(b).x();marker.z=Places.entrance(b).z();return Social.perceivable(s,a,marker);})
            .sorted(Comparator.comparingDouble(b->Math.hypot(b.x-a.x,b.z-a.z))).limit(6).map(b->b.name).toList();
        m.scene=new Scene(a.location,place==null?"Outdoors":place.name,place==null?"Travelling or exploring":place.description,
            indoors,indoors?Engine.weather(s.minute)+" outside; sheltered indoors":Engine.weather(s.minute),Engine.temperature(s.minute),daylight,
            place==null?List.of():List.copyOf(place.objects.values()),visiblePlaces);
        Map<String,String> current=new LinkedHashMap<>();
        current.put("location",Objects.toString(a.location,"road"));current.put("weather",m.scene.weather());current.put("daylight",daylight);
        current.put("hunger",a.hunger>=90?"very hungry":a.hunger>=70?"hungry":"manageable");
        current.put("energy",a.energy<20?"exhausted":a.energy<45?"tired":"adequate");
        for(Resident b:s.agents.values())if(Social.perceivable(s,a,b)&&Math.hypot(a.x-b.x,a.z-b.z)<=20)
            current.put("person:"+b.id,b.name+" is "+Engine.activity(b)+(Social.conversationAvailable(b)&&availability.getOrDefault(b.id,true)?" and available to talk":" and unavailable for a conversation"));
        if(m.intention!=null) {
            current.put("intentionDue",Boolean.toString(s.minute>=m.intention.reviewMinute()));
            if(m.intention.target()!=null&&s.buildings.containsKey(m.intention.target()))
                current.put("intentionArrival",Boolean.toString(m.intention.target().equals(a.location)));
        }
        boolean first=m.observedMinute<0;
        if(first)notice(m,"orientation","You are at "+m.scene.place()+" during "+daylight+". Notice your surroundings before choosing what matters.",40,s.minute);
        for(var fact:current.entrySet()) {
            String key=fact.getKey(),value=fact.getValue();
            if(Objects.equals(value,m.perceived.get(key)))continue;
            if(key.startsWith("person:"))notice(m,"people",value,60,s.minute);
            else if(key.equals("location")&&!first)notice(m,"place","Your location changed to "+m.scene.place(),50,s.minute);
            else if(key.equals("weather")&&!first)notice(m,"weather","Conditions changed: "+value,55,s.minute);
            else if(key.equals("daylight")&&!first)notice(m,"daylight","It is now "+value,30,s.minute);
            else if(key.equals("hunger")&&!value.equals("manageable"))notice(m,"needs","You are "+value+"; current hunger is "+Math.round(a.hunger),90,s.minute);
            else if(key.equals("energy")&&!value.equals("adequate"))notice(m,"needs","You are "+value+"; current energy is "+Math.round(a.energy),90,s.minute);
            else if(key.equals("intentionDue")&&value.equals("true"))notice(m,"intention","Time to review your chosen intention against actual outcomes",75,s.minute);
            else if(key.equals("intentionArrival")&&value.equals("true"))notice(m,"intention","You reached your intended destination; choose the next step rather than assuming the whole intention is complete",75,s.minute);
        }
        for(String old:m.perceived.keySet())if(old.startsWith("person:")&&!current.containsKey(old))
            notice(m,"people",m.perceived.get(old).split(" is ",2)[0]+" is no longer in view; their current location and intentions are unknown",45,s.minute);
        if(place!=null&&(!Objects.equals(a.location,m.perceived.get("location"))||first)) {
            Visit prior=m.visits.remove(a.location);
            m.visits.put(a.location,new Visit(a.location,place.name,prior==null?1:prior.count()+1,s.minute));
            while(m.visits.size()>VISIT_LIMIT)m.visits.remove(m.visits.keySet().iterator().next());
        }
        m.perceived=current;m.observedMinute=s.minute;
    }
    public static View view(Resident a,long minute) {
        Mind m=a.mind==null?new Mind():a.mind;
        boolean due=m.intention!=null&&minute>=m.intention.reviewMinute()&&m.lastDeliberatedMinute<m.intention.reviewMinute();
        boolean canReconsider=!a.paused&&ActionPerformance.awake(a)&&a.pending!=null;
        boolean activityReview=ActionPerformance.controllable(a)&&a.pending.reviewAfter>0&&minute>=a.pending.reviewAfter;
        boolean reconsider=canReconsider&&(activityReview||m.revision>m.reviewedRevision)&&(m.lastDeliberatedMinute<0||minute-m.lastDeliberatedMinute>=15);
        return new View(m.scene,m.notices.stream().filter(n->n.revision()>m.reviewedRevision).sorted(Comparator.comparingInt(Notice::priority).reversed()
            .thenComparing(Comparator.comparingLong(Notice::revision).reversed())).limit(8).toList(),m.intention,due,m.feeling,m.feelingMinute,
            List.copyOf(m.reflections),List.copyOf(m.outcomes),new ArrayList<>(m.visits.values()),reconsider);
    }
    public static void update(State s,Resident a,Update input) {
        Engine.require(!a.paused&&ActionPerformance.awake(a),"Review intentions while awake");
        Engine.require(input!=null,"Provide a cognition update");
        Engine.require(!(input.clearIntention()&&input.intention()!=null),"Choose a new intention or clear it, not both");
        Mind m=mind(a);Intention next=null;
        if(input.intention()!=null) {
            IntentionDraft draft=input.intention();
            String focus=required(draft.focus(),160,"focus"),step=required(draft.nextStep(),240,"nextStep"),reason=required(draft.reason(),240,"reason");
            int after=draft.reviewAfterMinutes()==null?60:draft.reviewAfterMinutes();
            Engine.require(after>=15&&after<=360,"Review the intention in fifteen to 360 simulated minutes");
            String target=optional(draft.target(),80,"target");
            Engine.require(target==null||s.buildings.containsKey(target)&&
                (s.buildings.get(target).capacity>1||target.equals(a.home)),"An intention target must be a known public place or your own home");
            next=new Intention(focus,step,reason,target,s.minute,s.minute+after);
        }
        String feeling=optional(input.feeling(),80,"feeling"),lesson=optional(input.lesson(),400,"lesson");
        if(lesson!=null)Engine.require(m.outcomes.stream().anyMatch(o->Objects.equals(o.id(),input.outcomeId())&&!o.status().equals("accepted")),
            "Ground a reflection in one of your own completed, cancelled or rejected recent outcomes");
        Relationships.Draft relationship=input.relationship()==null?null:Relationships.validate(s,a,input.relationship());
        // Validate everything before modifying an existing intention, self-assessment or reflection.
        if(input.clearIntention())m.intention=null;else if(next!=null)m.intention=next;
        if(input.clearIntention()||next!=null){m.perceived.remove("intentionDue");m.perceived.remove("intentionArrival");}
        if(feeling!=null){m.feeling=feeling;m.feelingMinute=s.minute;}
        if(relationship!=null)Relationships.apply(s,a,relationship);
        if(lesson!=null) {
            m.reflections.removeIf(r->r.outcomeId().equals(input.outcomeId()));
            m.reflections.add(new Reflection(lesson,input.outcomeId(),s.minute));
            while(m.reflections.size()>REFLECTION_LIMIT)m.reflections.removeFirst();
        }
    }
    private static String required(String value,int limit,String name) {String result=optional(value,limit,name);Engine.require(result!=null,"Provide "+name);return result;}
    private static String optional(String value,int limit,String name) {
        Engine.require(value==null||!value.isBlank()&&value.length()<=limit,"Keep "+name+" nonblank and within "+limit+" characters");
        return value==null?null:value.strip();
    }
    public static void action(Resident a,long minute,String id,String action,String target,String status,String result) {
        Mind m=mind(a);m.reviewedRevision=m.revision;m.lastDeliberatedMinute=minute;
        if(status.equals("completed"))DailyLife.completed(a,minute,action);
        m.outcomes.add(new Outcome(id,action,target,status,result,minute));
        // Keep the one in-flight receipt even if immediate conversation actions fill the history.
        while(m.outcomes.size()>OUTCOME_LIMIT) {
            int index=0;while(index<m.outcomes.size()&&"accepted".equals(m.outcomes.get(index).status()))index++;
            m.outcomes.remove(Math.min(index,m.outcomes.size()-1));
        }
        if(status.equals("rejected"))notice(m,"outcome","Your "+action+" was rejected: "+result,80,minute);
    }
    public static void complete(State s,Engine.Effects effects) {
        for(Resident a:s.agents.values())if(a.mind!=null)for(Engine.Completion completion:effects.completions) {
            Mind m=a.mind;
            for(int i=0;i<m.outcomes.size();i++) {
                Outcome old=m.outcomes.get(i);if(!old.id().equals(completion.id())||!old.status().equals("accepted"))continue;
                m.outcomes.set(i,new Outcome(old.id(),old.action(),old.target(),completion.status(),completion.result(),s.minute));
                if(completion.status().equals("completed"))DailyLife.completed(a,s.minute,old.action());
                notice(m,"outcome",old.action()+" "+completion.status()+": "+completion.result(),70,s.minute);
            }
        }
    }
}
