package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Collaborative, deterministic research and construction of useful world products. */
public final class Projects {
    private Projects() {}
    public static final int MEMBER_LIMIT=5;
    private static final int HUNGER_THRESHOLD=60,LOW_ENERGY_THRESHOLD=35,WETNESS_THRESHOLD=40;
    private static final Set<String> SUBJECTS=Collections.unmodifiableSet(new LinkedHashSet<>(BookCatalog.books().stream().map(BookCatalog.Book::subject).toList()));
    private static final List<Recipe> RECIPES=List.of(
        new Recipe("nourishment","Nourishment product",List.of("gardening","biology","chemistry","science","engineering","business","economics","finance","design","environment"),600,60,"Consumes one unit to reduce hunger by up to 40 points."),
        new Recipe("weather-care","Weather care product",List.of("physics","chemistry","engineering","technology","science","environment","geography","design","business","finance","economics"),700,60,"Consumes one unit to reduce wetness by up to 60 points and protect against rain for 120 minutes."),
        new Recipe("study-aid","Study aid",List.of("computing","technology","engineering","mathematics","communication","psychology","philosophy","history","literature","design","business","economics","finance","science"),500,60,"Consumes one unit to provide 120 minutes of study support: one extra study minute per four actual reading minutes, within finite book mastery limits."),
        new Recipe("restorative-art","Restorative art",List.of("arts","music","literature","history","philosophy","communication","psychology","design","business","economics","finance"),500,60,"Consumes one unit to restore up to 20 energy points."),
        new Recipe("recovery-kit","Recovery kit",List.of("biology","chemistry","physics","science","engineering","environment","psychology","design","business","economics","finance","geography"),600,60,"Consumes one unit to restore up to 10 energy points and reduce wetness by up to 30 points.")
    );

    public record Recipe(String id,String title,List<String> subjects,long unitCost,int buildMinutes,String effects) {}
    public static class Project {
        public String id,founder,title,subject,problem,description,location,recipe;
        public String status="researching",productId="",businessId="";
        public Set<String> members=new LinkedHashSet<>();
        public Set<String> businessEndorsements=new LinkedHashSet<>();
        public Map<String,ResearchFinding> research=new LinkedHashMap<>();
        public List<String> concepts=new ArrayList<>();
        public long createdMinute;
        public int prototypesBuilt;
    }
    public static class Invitation {
        public String id,project,inviter,invited,message,status="proposed";
        public long minute;
    }
    public static class Product {
        public String id,project,title,subject,recipe,description,effects;
        public long unitCost,createdMinute;
    }
    public static class ResearchFinding {
        public String id,project,subject,method,lessonKey,concept,result;
        public long minute;
        public Map<String,Double> evidence=new LinkedHashMap<>();
        public String subject() {return subject;}
        public String lessonKey() {return lessonKey;}
        public String concept() {return concept;}
        public String method() {return method;}
        public String id() {return id;}
        public String project() {return project;}
        public String result() {return result;}
        public long minute() {return minute;}
        public Map<String,Double> evidence() {return evidence;}
    }
    public record ProjectView(String id,String founder,String title,String subject,String problem,String description,
                              String location,String recipe,List<String> members,String status,String productId,
                              List<ResearchFinding> research,long createdMinute,List<String> concepts,
                              List<String> businessEndorsements,String businessId) {}
    public record InterestedResident(String id,String name,List<String> interests) {}
    public record Problem(String id,String kind,String description,List<String> subjects,Map<String,Double> evidence) {}
    public record View(List<Recipe> recipes,List<ProjectView> projects,List<Invitation> invitations,
                       List<InterestedResident> interestedResidents,List<Product> products,
                       List<Ventures.BusinessView> businesses,List<Problem> problems,List<Problem> sharedConditions,List<String> interests) {}
    public record PublicView(List<Recipe> recipes,List<ProjectView> projects,List<Product> products,List<Ventures.BusinessView> businesses) {}

    public static List<Recipe> recipes() {return RECIPES;}
    public static Recipe recipe(String id) {
        return RECIPES.stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new Engine.Invalid("Choose a supported recipe from innovations.recipes"));
    }
    public static Project project(State s,String id) {
        Project p=s.projects.get(id);Engine.require(p!=null,"Choose an existing project ID");return p;
    }
    public static Project member(State s,Resident a,String id) {
        Project p=project(s,id);Engine.require(p.members.contains(a.id),"Only accepted project members can perform this action");return p;
    }
    public static Product productFor(State s,Project p) {
        Product product=s.products.get(p.productId);Engine.require(product!=null&&p.id.equals(product.project),"Build a real prototype before using this project product");return product;
    }
    public static boolean tested(Project p) {return p.research.values().stream().anyMatch(f->"test".equals(f.method));}
    private static void capable(Resident a,String subject,boolean create) {
        Library.refreshKnowledge(a);SubjectKnowledge k=a.knowledge.get(subject);
        Engine.require(k!=null&&(create?k.getCanCreate():k.getCanReason()),"Continue reading and studying "+subject+" before "+(create?"building":"researching")+" this project");
    }
    private static void free(State s,Resident a) {
        Engine.require(!a.paused&&a.pending==null,"Finish your current activity before working on a project");
        Engine.require(!Conversations.active(s,a),"End your conversation before starting research or construction");
    }
    public static Building requireWorkplace(State s,Resident a,String location,int minutes) {
        Building b=s.buildings.get(location);
        Engine.require(b!=null&&Places.enclosed(b)&&List.of("office","studio","market","workshop").contains(b.type),"Choose a public office, studio, market or workshop for this project");
        Engine.at(a,location);Places.requireOpen(b,s.minute);
        Engine.require(minutes>=0&&minutes<=300,"Work must take at most 300 minutes");
        for(int i=0;i<minutes;i++)Engine.require(Places.open(b,s.minute+i),"The workplace must stay open for the whole requested activity");
        return b;
    }
    private static void needs(Resident a) {Engine.require(a.energy>=5&&a.hunger<95,"Eat or rest before researching or building");}

    public static String declareInterest(State s,Resident a,String subject,String operation,Engine.Effects e) {
        Engine.require(SUBJECTS.contains(subject),"Choose a subject from the library catalog");
        Engine.require(operation==null||operation.isBlank()||List.of("add","remove").contains(operation),"Interest item must be add or remove");
        boolean remove="remove".equals(operation);
        if(remove)a.interests.remove(subject);else a.interests.add(subject);
        e.facts.add(new Engine.Fact("interest",a.id,subject,a.name+(remove?" is no longer seeking collaborators in ":" is interested in collaborating on ")+subject+"."));
        return remove?"Subject interest removed":"Subject interest declared; interested peers can discover it";
    }
    public static String start(State s,Resident a,String creationId,String recipeId,String location,String id,Engine.Effects e) {
        free(s,a);Creation creation=a.creations.get(creationId);
        Engine.require(creation!=null,"Choose one of your own learned-subject creations");
        Recipe r=recipe(recipeId);
        Engine.require(r.subjects().contains(creation.subject()),"Choose a recipe that supports this creation's subject");
        capable(a,creation.subject(),true);requireWorkplace(s,a,location,0);
        Engine.require(!s.projects.containsKey(id),"This project ID already exists");
        Engine.require(s.projects.values().stream().noneMatch(p->a.id.equals(p.founder)&&creation.title().equals(p.title)&&creation.content().equals(p.description)),"This creation already has a world project; continue it instead");
        Reasoning reasoning=a.reasoning.get(creation.reasoningId());
        Engine.require(reasoning!=null&&creation.subject().equals(reasoning.subject()),"A project needs its original subject-grounded reasoning and problem");
        Project p=new Project();p.id=id;p.founder=a.id;p.title=creation.title();p.subject=creation.subject();
        p.problem=reasoning.problem();p.description=creation.content();p.location=location;p.recipe=r.id();p.createdMinute=s.minute;
        p.concepts.addAll(creation.concepts());p.members.add(a.id);s.projects.put(id,p);a.interests.add(p.subject);
        e.facts.add(new Engine.Fact("project",a.id,id,a.name+" started "+p.title+" at "+s.buildings.get(location).name+" to address: "+p.problem+". The project needs observed evidence and a built prototype."));
        return "Project started: "+id+"; its problem and design are public. Research it before building a prototype";
    }
    public static String invite(State s,Resident a,String residentId,String projectId,String message,String id,Engine.Effects e) {
        Project p=member(s,a,projectId);Resident other=s.agents.get(residentId);Engine.together(a,other);
        Engine.require(!Engine.present(p.businessId),"This project already launched; its co-owners are fixed");
        Conversation conversation=s.conversations.get(Conversations.key(a.id,residentId));
        Engine.require(conversation!=null&&!conversation.turns.isEmpty(),"Have a real conversation with this nearby resident before inviting them");
        Engine.require(!other.paused&&other.pending==null,"This resident is busy; invite them when they are available");
        Engine.require(other.interests.contains(p.subject),"This resident has not declared interest in the project's subject");
        Engine.require(!p.members.contains(other.id),"This resident is already an accepted project member");
        Engine.require(p.members.size()<MEMBER_LIMIT,"A project can have at most five members");
        Engine.require(message!=null&&message.strip().length()>=20&&message.length()<=500,"Explain the proposal in 20 to 500 characters");
        Engine.require(!s.projectInvitations.containsKey(id),"This invitation ID already exists");
        Engine.require(s.projectInvitations.values().stream().noneMatch(i->projectId.equals(i.project)&&residentId.equals(i.invited)&&"proposed".equals(i.status)),"This resident already has an unanswered invitation to this project");
        Invitation invitation=new Invitation();invitation.id=id;invitation.project=p.id;invitation.inviter=a.id;invitation.invited=other.id;invitation.minute=s.minute;
        invitation.message=message.strip()+" Joining permits project research and building. Launching a business would offer equal co-ownership and requires your separate explicit endorsement of costs.";
        s.projectInvitations.put(id,invitation);
        e.facts.add(new Engine.Fact("project_invitation",a.id,other.id,a.name+" invited "+other.name+" to collaborate on "+p.title+"; awaiting their decision."));
        return "Project invitation sent: "+id+"; the invited resident must choose whether to accept";
    }
    public static String respond(State s,Resident a,String id,boolean accept,Engine.Effects e) {
        Invitation i=s.projectInvitations.get(id);
        Engine.require(i!=null&&a.id.equals(i.invited)&&"proposed".equals(i.status),"Only the invited resident can answer this pending invitation");
        Project p=project(s,i.project);
        if(accept) {
            Engine.require(!Engine.present(p.businessId),"The project already launched; its co-owners are fixed");
            Engine.require(a.interests.contains(p.subject),"Declare interest in this subject before joining");
            capable(a,p.subject,false);
            Engine.require(p.members.size()<MEMBER_LIMIT&&!p.members.contains(a.id),"The project is full or you already joined");
        }
        i.status=accept?"accepted":"declined";
        if(accept){p.members.add(a.id);p.businessEndorsements.clear();}
        e.facts.add(new Engine.Fact("project_invitation",a.id,i.inviter,a.name+(accept?" accepted":" declined")+" the invitation to "+p.title+(accept?". Business ownership still requires separate consent.":".")));
        return accept?"Joined project; research and building are voluntary, and business launch requires separate endorsement":"Project invitation declined";
    }
    public static String research(State s,Resident a,String projectId,String method,int minutes,String id,Engine.Effects e) {
        free(s,a);Project p=member(s,a,projectId);capable(a,p.subject,false);
        Engine.require(method!=null&&List.of("observation","test","economics").contains(method),"Research method must be observation, test or economics");
        Engine.require(minutes>=30&&minutes<=100,"Research for thirty to one hundred minutes");
        requireWorkplace(s,a,p.location,minutes);needs(a);
        if("test".equals(method)) {
            Product product=productFor(s,p);Engine.require(a.inventory.getOrDefault(product.id,0)>0,"A test requires one prototype in your own inventory; it is consumed when testing completes");
        }
        Pending pending=Engine.pending(id,"RESEARCH_PROJECT",p.id,s.minute+minutes);pending.startedAt=s.minute;pending.projectMethod=method;
        a.pending=pending;
        e.facts.add(new Engine.Fact("research",a.id,p.id,a.name+" began "+method+" research for "+p.title+" ("+minutes+" minutes)."));
        return "Research started; evidence and a finite lesson are recorded after "+minutes+" minutes of completed work";
    }
    public static String build(State s,Resident a,String projectId,String id,Engine.Effects e) {
        free(s,a);Project p=member(s,a,projectId);capable(a,p.subject,true);Recipe r=recipe(p.recipe);
        Engine.require(p.research.values().stream().anyMatch(f->"observation".equals(f.method)),"Complete observation research before building: establish the actual problem first");
        requireWorkplace(s,a,p.location,r.buildMinutes());needs(a);
        long cost=2*r.unitCost();Engine.require(a.money>=cost,"Insufficient funds for the prototype's material cost of "+cost+" cents");
        Engine.require(!s.accounts.containsKey("escrow:"+id),"This prototype reservation ID already exists");
        Pending pending=Engine.pending(id,"BUILD_PROJECT",p.id,s.minute+r.buildMinutes());pending.startedAt=s.minute;pending.reservedCost=cost;
        Engine.pay(s,a.id,"escrow:"+id,cost,"prototype_material_reservation",e);a.pending=pending;
        e.facts.add(new Engine.Fact("construction",a.id,p.id,a.name+" began building a prototype of "+p.title+"; "+cost+" cents reserved for materials."));
        return "Prototype build started; one usable product will enter your inventory after "+r.buildMinutes()+" minutes";
    }
    public static String use(State s,Resident a,String productId,Engine.Effects e) {
        Product product=s.products.get(productId);Engine.require(product!=null,"Choose an existing product ID from innovations.products");
        Engine.require(a.inventory.getOrDefault(productId,0)>0,"You do not own a unit of this product");
        recipe(product.recipe);Map<String,Double> before=needEvidence(a);applyProductEffect(s,a,product);consume(a,productId);
        String result=productResult(product,before,needEvidence(a));
        e.facts.add(new Engine.Fact("product_use",a.id,productId,a.name+" used one unit of "+product.title+" with its supported "+product.recipe+" effect."));return result;
    }
    public static void applyProductEffect(State s,Resident a,Product product) {
        switch(recipe(product.recipe).id()) {
            case "nourishment" -> {
                a.hunger=Math.max(0,a.hunger-40);a.lastMealMinute=s.minute;a.hungerAfterLastMeal=a.hunger;
                if(a.home!=null&&a.home.equals(a.location))a.hungerWakeNeedsFood=false;
            }
            case "weather-care" -> {a.wetness=Math.max(0,a.wetness-60);a.weatherProtectionUntil=Math.max(a.weatherProtectionUntil,s.minute+120);}
            case "study-aid" -> a.studyBoostUntil=Math.max(a.studyBoostUntil,s.minute+120);
            case "restorative-art" -> a.energy=Math.min(100,a.energy+20);
            case "recovery-kit" -> {a.energy=Math.min(100,a.energy+10);a.wetness=Math.max(0,a.wetness-30);}
            default -> throw new Engine.Invalid("Unsupported product recipe");
        }
    }
    private static void consume(Resident a,String productId) {
        int units=a.inventory.getOrDefault(productId,0);if(units<=1)a.inventory.remove(productId);else a.inventory.put(productId,units-1);
    }
    private static Map<String,Double> needEvidence(Resident a) {
        Map<String,Double> evidence=new LinkedHashMap<>();evidence.put("hunger",a.hunger);evidence.put("energy",a.energy);evidence.put("wetness",a.wetness);
        evidence.put("studyBoostUntil",(double)a.studyBoostUntil);evidence.put("weatherProtectionUntil",(double)a.weatherProtectionUntil);return evidence;
    }
    private static String productResult(Product p,Map<String,Double> before,Map<String,Double> after) {
        StringBuilder result=new StringBuilder("Consumed one unit. ");
        for(String key:List.of("hunger","energy","wetness"))if(!Objects.equals(before.get(key),after.get(key)))
            result.append(key).append(" ").append(round(before.get(key))).append(" → ").append(round(after.get(key))).append("; ");
        if("study-aid".equals(p.recipe))result.append("study support lasts until world minute ").append(after.get("studyBoostUntil").longValue()).append("; ");
        if("weather-care".equals(p.recipe))result.append("rain protection lasts until world minute ").append(after.get("weatherProtectionUntil").longValue()).append("; ");
        return result.toString().strip();
    }
    private static double round(double value) {return Math.round(value*100.0)/100.0;}

    /** Called once per elapsed minute by Engine, before generic pending completion. */
    public static void tick(State s,Resident a,Engine.Effects e) {
        Pending pending=a.pending;if(pending==null||!List.of("RESEARCH_PROJECT","BUILD_PROJECT").contains(pending.type))return;
        Project p=s.projects.get(pending.target);Building b=p==null?null:s.buildings.get(p.location);
        boolean accessible=p!=null&&p.members.contains(a.id)&&p.location.equals(a.location)&&Places.open(b,s.minute-1);
        if(a.paused||!accessible||a.energy<=0||a.hunger>=98) {interrupt(s,a,e,"Activity interrupted; no finding or prototype was completed");return;}
        long duration=pending.until-pending.startedAt;
        if(duration<30||duration>100||"RESEARCH_PROJECT".equals(pending.type)&&(pending.projectMethod==null||!List.of("observation","test","economics").contains(pending.projectMethod))) {
            interrupt(s,a,e,"Activity interrupted because its saved work specification is invalid");return;
        }
        if("BUILD_PROJECT".equals(pending.type)&&s.accounts.getOrDefault("escrow:"+pending.id,0L)!=pending.reservedCost) {
            interrupt(s,a,e,"Build interrupted because its reserved material funds are unavailable");return;
        }
        if(s.minute<=pending.startedAt+pending.workMinutes)return;
        pending.workMinutes++;a.energy=Math.max(0,a.energy-.05);
        if(pending.workMinutes<duration)return;
        if("BUILD_PROJECT".equals(pending.type)) {
            Recipe r=recipe(p.recipe);Product product=s.products.get("product:"+p.id);
            if(pending.reservedCost!=r.unitCost()*2||a.inventory.getOrDefault("product:"+p.id,0)==Integer.MAX_VALUE||p.prototypesBuilt==Integer.MAX_VALUE) {
                interrupt(s,a,e,"Build interrupted because its materials or inventory limit is invalid");return;
            }
            if(product==null) {
                product=new Product();product.id="product:"+p.id;product.project=p.id;product.title=p.title;product.subject=p.subject;product.recipe=p.recipe;
                product.description=p.description;product.effects=r.effects();product.unitCost=r.unitCost();product.createdMinute=s.minute;s.products.put(product.id,product);
            }
            p.productId=product.id;p.prototypesBuilt++;if(!Engine.present(p.businessId))p.status=tested(p)?"tested":"prototyped";
            a.inventory.merge(product.id,1,Integer::sum);
            long cost=s.accounts.getOrDefault("escrow:"+pending.id,0L);
            if(cost>0)Engine.pay(s,"escrow:"+pending.id,"materials",cost,"prototype_materials",e);s.accounts.remove("escrow:"+pending.id);
            e.facts.add(new Engine.Fact("construction",a.id,p.id,a.name+" finished one usable prototype of "+p.title+"; it is in their inventory."));
            Engine.finish(a,e,"Built one usable "+product.id+" for "+pending.reservedCost+" cents; use it or test it with RESEARCH_PROJECT test");
        } else {
            if("test".equals(pending.projectMethod)) {
                Product product=s.products.get(p.productId);
                if(product==null||a.inventory.getOrDefault(product.id,0)<=0) {interrupt(s,a,e,"Testing interrupted; the required owned prototype is no longer available");return;}
            }
            ResearchFinding finding=completeResearch(s,a,p,pending);
            p.research.put(a.id+":"+finding.method,finding);
            a.researchFindings.putIfAbsent(finding.lessonKey,finding);
            Library.refreshKnowledge(a);if("test".equals(finding.method)&&!Engine.present(p.businessId))p.status="tested";
            e.facts.add(new Engine.Fact("research",a.id,p.id,a.name+" completed "+finding.method+" research for "+p.title+" and retained measured evidence for project members."));
            Engine.finish(a,e,"Completed "+finding.method+" research; recorded real evidence and a finite "+p.subject+" research lesson");
        }
    }
    public static void interrupt(State s,Resident a,Engine.Effects e,String result) {
        Pending pending=a.pending;if(pending==null)return;
        long reserved=s.accounts.getOrDefault("escrow:"+pending.id,0L);
        if(reserved>0)Engine.pay(s,"escrow:"+pending.id,a.id,reserved,"prototype_material_refund",e);
        s.accounts.remove("escrow:"+pending.id);
        e.completions.add(new Engine.Completion(pending.id,"cancelled",result+(reserved>0?"; reserved materials refunded":"")));a.pending=null;
    }
    private static ResearchFinding completeResearch(State s,Resident a,Project p,Pending pending) {
        ResearchFinding f=new ResearchFinding();f.id=pending.id;f.project=p.id;f.subject=p.subject;f.method=pending.projectMethod;f.minute=s.minute;
        f.lessonKey=p.subject+":"+p.recipe+":"+f.method;
        if("observation".equals(f.method)) {
            Map<String,Double> evidence=needEvidence(a);evidence.putAll(communityEvidence(s));evidence.put("rainNow",Engine.weather(s.minute).equals("Rain")?1.0:0.0);f.evidence=evidence;
            f.concept="Observe the current "+p.recipe+" problem before choosing a design; measure needs and available supplies rather than assuming demand.";
            f.result="Observed own hunger "+round(a.hunger)+", energy "+round(a.energy)+" and wetness "+round(a.wetness)+"; "+evidence.get("hungryResidents").intValue()+" residents currently have hunger ≥ 60, "+evidence.get("lowEnergyResidents").intValue()+" have energy ≤ 35 and "+evidence.get("wetResidents").intValue()+" have wetness ≥ 40. These needs are evidence, not purchase commitments.";
        } else if("test".equals(f.method)) {
            Product product=productFor(s,p);Map<String,Double> before=needEvidence(a);applyProductEffect(s,a,product);consume(a,product.id);Map<String,Double> after=needEvidence(a);
            before.forEach((key,value)->f.evidence.put("before"+Character.toUpperCase(key.charAt(0))+key.substring(1),value));
            after.forEach((key,value)->f.evidence.put("after"+Character.toUpperCase(key.charAt(0))+key.substring(1),value));
            f.evidence.put("unitsConsumed",1.0);f.concept="Test a "+p.recipe+" prototype by measuring the actual before and after state; one observed effect does not prove general demand.";
            f.result=productResult(product,before,after)+" This measures the supported effect on this tester; it does not demonstrate sales or effectiveness for every resident.";
        } else {
            Recipe r=recipe(p.recipe);f.evidence.put("unitMaterialCostCents",(double)r.unitCost());f.evidence.put("prototypeMaterialCostCents",(double)(2*r.unitCost()));
            long examplePrice=r.unitCost()*2;f.evidence.put("examplePriceCents",(double)examplePrice);f.evidence.put("exampleMaterialMarginCents",(double)(examplePrice-r.unitCost()));
            f.evidence.put("productionMinutesPerUnit",30.0);f.evidence.put("employeeWageFor30MinutesCents",500.0);
            f.evidence.put("exampleMarginAfterPaidLaborCents",(double)(examplePrice-r.unitCost()-500));
            String substitute=switch(p.recipe){case "nourishment"->"meal";case "weather-care"->"raincoat";default->"";};
            long marketUnits=0,minPrice=Long.MAX_VALUE;
            if(!substitute.isEmpty())for(Building b:s.buildings.values())if(Places.open(b,s.minute)&&b.prices.containsKey(substitute)) {
                marketUnits+=Math.max(0,b.stock.getOrDefault(substitute,0));minPrice=Math.min(minPrice,b.prices.get(substitute));
            }
            f.evidence.put("observedSubstituteStock",(double)marketUnits);
            if(minPrice!=Long.MAX_VALUE)f.evidence.put("observedLowestSubstitutePriceCents",(double)minPrice);
            f.concept="Compare "+p.recipe+" material and labor costs with an explicit hypothetical price and actual available substitutes; revenue requires completed purchases.";
            f.result="Materials cost "+r.unitCost()+" cents per manufactured unit. At an illustrative price of "+examplePrice+" cents, margin is "+(examplePrice-r.unitCost())+" cents before labor and "+(examplePrice-r.unitCost()-500)+" after a 30-minute paid shift at 1000 cents/hour. "+(minPrice==Long.MAX_VALUE?"No priced comparable substitute was observed.":"Open stores currently list "+marketUnits+" units of "+substitute+" from "+minPrice+" cents; this is supply, not inferred demand.")+" No customers, sales or profit have been credited.";
        }
        return f;
    }
    private static Map<String,Double> communityEvidence(State s) {
        Map<String,Double> evidence=new LinkedHashMap<>();evidence.put("residents",(double)s.agents.size());
        evidence.put("hungryResidents",(double)s.agents.values().stream().filter(a->a.hunger>=HUNGER_THRESHOLD).count());
        evidence.put("lowEnergyResidents",(double)s.agents.values().stream().filter(a->a.energy<=LOW_ENERGY_THRESHOLD).count());
        evidence.put("wetResidents",(double)s.agents.values().stream().filter(a->a.wetness>=WETNESS_THRESHOLD).count());
        evidence.put("mealStock",(double)s.buildings.values().stream().mapToLong(b->Math.max(0,b.stock.getOrDefault("meal",0))).sum());return evidence;
    }
    public static List<Problem> problems(State s,Resident a) {
        if(a==null)return List.of();
        List<Problem> problems=new ArrayList<>();
        if(a.hunger>=HUNGER_THRESHOLD)
            problems.add(new Problem("hunger","hunger","Your hunger is "+round(a.hunger)+"/100 (high).",List.of("science","biology","gardening"),Map.of("hunger",a.hunger)));
        if(a.energy<=LOW_ENERGY_THRESHOLD)
            problems.add(new Problem("low-energy","low-energy","Your energy is "+round(a.energy)+"/100 (low).",List.of("biology","psychology"),Map.of("energy",a.energy)));
        if(a.wetness>=WETNESS_THRESHOLD)
            problems.add(new Problem("wetness","wetness","Your wetness is "+round(a.wetness)+"/100 (high).",List.of("environment","physics","engineering"),Map.of("wetness",a.wetness)));
        List<BookLoan> overdue=a.bookLoans.values().stream().filter(loan->s.minute>=loan.dueMinute).toList();
        if(!overdue.isEmpty()) {
            long longestOverdueMinutes=overdue.stream().mapToLong(loan->s.minute-loan.dueMinute).max().orElse(0);
            List<BookCatalog.Book> books=BookCatalog.books().stream().filter(book->overdue.stream().anyMatch(loan->book.id().equals(loan.book))).toList();
            String titles=String.join(", ",overdue.stream().map(loan->books.stream().filter(book->book.id().equals(loan.book)).findFirst().map(BookCatalog.Book::title).orElse(loan.book)).toList());
            problems.add(new Problem("overdue-books","overdue-books","You have "+overdue.size()+" overdue library "+(overdue.size()==1?"book":"books")+": "+titles+". The longest overdue loan is "+longestOverdueMinutes+" minutes overdue. Further borrowing and reading overdue books are blocked until returned.",books.stream().map(BookCatalog.Book::subject).distinct().toList(),Map.of("overdueBooks",(double)overdue.size(),"longestOverdueMinutes",(double)longestOverdueMinutes)));
        }
        return List.copyOf(problems);
    }
    public static List<Problem> sharedConditions(State s) {
        Map<String,Double> evidence=communityEvidence(s);
        return List.of(
            new Problem("community-needs","community-needs","City snapshot: "+evidence.get("residents").intValue()+" residents; "+evidence.get("hungryResidents").intValue()+" have hunger ≥ "+HUNGER_THRESHOLD+"/100, "+evidence.get("lowEnergyResidents").intValue()+" have energy ≤ "+LOW_ENERGY_THRESHOLD+"/100 and "+evidence.get("wetResidents").intValue()+" have wetness ≥ "+WETNESS_THRESHOLD+"/100. Store meal stock: "+evidence.get("mealStock").longValue()+" units.",List.of("business","economics","design","science","arts"),evidence),
            new Problem("weather","weather","Current weather is "+Engine.weather(s.minute)+" at "+Engine.temperature(s.minute)+"°C.",List.of("environment","physics","engineering","geography"),Map.of("rainNow",Engine.weather(s.minute).equals("Rain")?1.0:0.0,"temperatureC",(double)Engine.temperature(s.minute))));
    }
    private static ProjectView projectView(Project p,Resident a) {
        boolean member=a!=null&&p.members.contains(a.id);
        return new ProjectView(p.id,p.founder,p.title,p.subject,p.problem,p.description,p.location,p.recipe,List.copyOf(p.members),p.status,p.productId,
            member?List.copyOf(p.research.values()):List.of(),p.createdMinute,List.copyOf(p.concepts),member?List.copyOf(p.businessEndorsements):List.of(),p.businessId);
    }
    public static View view(State s,Resident a) {
        return new View(RECIPES,s.projects.values().stream().map(p->projectView(p,a)).toList(),
            s.projectInvitations.values().stream().filter(i->a.id.equals(i.invited)||a.id.equals(i.inviter)).toList(),
            s.agents.values().stream().filter(b->!a.id.equals(b.id)&&!b.interests.isEmpty()).map(b->new InterestedResident(b.id,b.name,List.copyOf(b.interests))).toList(),
            List.copyOf(s.products.values()),Ventures.views(s,a),problems(s,a),sharedConditions(s),List.copyOf(a.interests));
    }
    public static PublicView publicView(State s) {
        return new PublicView(RECIPES,s.projects.values().stream().map(p->projectView(p,null)).toList(),List.copyOf(s.products.values()),Ventures.views(s,null));
    }
}
