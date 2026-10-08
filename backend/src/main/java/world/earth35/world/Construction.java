package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;
import world.earth35.world.IslandGeography.Point;

/** Funded construction opportunities. Only residents' accepted work advances a site. */
public final class Construction {
    private Construction() {}
    public static final int START_MINUTE=360,END_MINUTE=1200;
    public static final long HOURLY_WAGE=2000,MATERIALS_PER_HOUR=1000;
    @io.swagger.v3.oas.annotations.media.Schema(name="ConstructionOption")
    public record Option(String type,int workHours,long materialCostPerHourCents,long hourlyWageCents,long budgetCents,long selfBuildBudgetCents) {}
    private static final List<Option> OPTIONS=List.of(option("house",16),option("apartment",32),option("office",24),option("bakery",8),option("fruit-shop",8));
    private static Option option(String type,int hours) {return new Option(type,hours,MATERIALS_PER_HOUR,HOURLY_WAGE,hours*(HOURLY_WAGE+MATERIALS_PER_HOUR),hours*MATERIALS_PER_HOUR);}
    public static class Site {
        public String id,sponsor,plannedType,status="building";
        public String laborMode="paid",loanId;
        public int requiredHours,completedHours,reservedHours;
        public long hourlyWageCents=HOURLY_WAGE,materialCostPerHourCents=MATERIALS_PER_HOUR;
    }
    public record WorkerView(String id,String name,int workMinutes,int requiredMinutes,boolean selfBuild) {}
    public record SiteView(String id,String name,String plannedType,String sponsor,String status,int completedHours,int requiredHours,int reservedHours,
                           long hourlyWageCents,boolean acceptingWorkers,String entranceTarget,int workStartMinute,int workEndMinute,long unreservedBudgetCents,
                           String laborMode,int completedWorkMinutes,int requiredWorkMinutes,List<WorkerView> workers) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="ConstructionView")
    public record View(List<Option> options,List<SiteView> sites) {}
    public static View view(State s) {
        return new View(OPTIONS,s.construction.values().stream().filter(site->s.buildings.containsKey(site.id)).map(site->{
            Building b=s.buildings.get(site.id);var door=Places.entrance(b);
            long balance=s.accounts.getOrDefault(account(site.id),0L);
            List<WorkerView> workers=s.agents.values().stream().filter(a->{
                Pending p=a.pending;return p!=null&&"BUILD_CONSTRUCTION".equals(p.type)&&site.id.equals(p.target)
                    &&!a.paused&&atEntrance(s,a,b)&&s.accounts.getOrDefault("escrow:construction:"+p.id,0L)==cost(site);
            }).map(a->new WorkerView(a.id,a.name,a.pending.workMinutes,60,"self".equals(site.laborMode))).toList();
            return new SiteView(site.id,b.name,site.plannedType,site.sponsor,site.status,site.completedHours,site.requiredHours,site.reservedHours,
                site.hourlyWageCents,"building".equals(site.status)&&!"self".equals(site.laborMode)&&site.completedHours+site.reservedHours<site.requiredHours&&balance>=cost(site),
                "point:"+door.x()+","+door.z(),START_MINUTE,END_MINUTE,balance,
                site.laborMode,site.completedHours*60+workers.stream().mapToInt(WorkerView::workMinutes).sum(),site.requiredHours*60,workers);
        }).toList());
    }
    static String account(String site) {return "construction-fund:"+site;}
    private static long cost(Site site) {return site.hourlyWageCents+site.materialCostPerHourCents;}
    private static Option option(String type) {
        return OPTIONS.stream().filter(o->o.type().equals(type)).findFirst().orElseThrow(()->new Engine.Invalid("Choose a construction type from livelihood.construction.options"));
    }
    private static Site site(Building b,String sponsor,Option option) {
        Site site=new Site();site.id=b.id;site.sponsor=sponsor;site.plannedType=option.type();site.requiredHours=option.workHours();return site;
    }
    /** A once-only city capital allocation; it creates opportunities, never workers or progress. */
    public static void initializeCitySites(State s,Engine.Effects e) {
        for(Building b:s.buildings.values()) {
            if(!"construction".equals(b.type)||s.construction.containsKey(b.id))continue;
            Option option=option(b.plannedType);Site site=site(b,"city",option);
            Engine.pay(s,"city-construction-issuance",account(b.id),option.budgetCents(),"construction_capital",e);
            s.construction.put(site.id,site);
        }
    }
    public static String start(State s,Resident a,String target,String type,String name,Long capital,String id,Engine.Effects e) {
        return start(s,a,target,type,name,capital,null,null,id,e);
    }
    public static String start(State s,Resident a,String target,String type,String name,Long capital,Integer loanYears,String laborMode,String id,Engine.Effects e) {
        Engine.require(!a.paused&&a.pending==null,"Finish your current action before initiating construction");
        Engine.require(!Conversations.active(s,a),"End the current conversation before initiating construction");
        Option option=option(type);
        Engine.require(s.geographyVersion>=2,"New construction requires the island geography");
        Building point=IslandGeography.coordinate(target);
        Engine.require(point!=null,"Choose a dry island location with target point:x,z");
        Engine.require(name!=null&&!name.isBlank()&&name.strip().length()<=80,"Name the construction project in up to 80 characters");
        Engine.require(!s.buildings.containsKey("site:"+id),"This construction project already exists");
        Engine.require(s.construction.values().stream().filter(p->a.id.equals(p.sponsor)&&"building".equals(p.status)).count()<5,"Finish an existing sponsored site before opening more than five");
        String labor=laborMode==null?"paid":laborMode;
        Engine.require(List.of("paid","self").contains(labor),"Choose laborMode paid for funded workers or self for your own materials-only building work");
        long budget="self".equals(labor)?option.selfBuildBudgetCents():option.budgetCents();
        long funding=capital==null?budget:capital;
        Engine.require(funding>=budget&&funding<=1000000,"Fund the complete chosen material and labor budget shown in livelihood.construction.options");
        requireClear(s,point.x,point.z);
        Building b=new Building("site:"+id,name.strip(),"construction","Development",point.x,point.z,0);
        b.plannedType=option.type();b.entrance=Places.entrance(b);
        b.description="A resident-funded "+option.type()+" project; progress requires actual on-site building work.";
        Site site=site(b,a.id,option);
        site.laborMode=labor;if("self".equals(labor))site.hourlyWageCents=0;
        site.loanId=Loans.finance(s,a,funding,loanYears,"construction:"+b.id,id,e);
        Engine.pay(s,a.id,account(b.id),funding,"construction_capital",e);
        s.buildings.put(b.id,b);s.construction.put(site.id,site);
        e.facts.add(new Engine.Fact("construction",a.id,b.id,a.name+" initiated "+b.name+("self".equals(labor)?"; the owner will build using funded materials.":"; paid building opportunities are available.")));
        return "Construction funded: "+b.id+"; "+("self".equals(labor)?"the owner must choose BUILD_CONSTRUCTION at its entrance":"builders may independently choose BUILD_CONSTRUCTION at its entrance")+
            (site.loanId==null?"":"; shortfall financed by "+site.loanId);
    }
    private static void requireClear(State s,double x,double z) {
        if(s.geographyVersion>=4)Engine.require(IslandLandscapes.footprintClear(x,z,4),"Keep the complete building footprint clear of marked trails and protected cliffs");
        Engine.require(IslandGeography.roadDistance(x,z)>=8,"Choose a site clear of roads and crossings");
        for(double dx:new double[]{-4,4})for(double dz:new double[]{-4,4})
            Engine.require(IslandGeography.dryLand(x+dx,z+dz),"The entire building footprint must be on dry land");
        Engine.require(IslandGeography.dryLand(x,z+3.6),"The entrance must be on dry land");
        Building footprint=new Building();footprint.x=x;footprint.z=z;
        for(int i=0;i<IslandGeography.COAST.size();i++)
            Engine.require(!IslandNavigation.intersects(IslandGeography.COAST.get(i),IslandGeography.COAST.get((i+1)%IslandGeography.COAST.size()),footprint,4),"Keep the entire footprint away from the coastline");
        // Corner samples alone miss narrow water crossing the middle of a footprint.
        // The expanded rectangle conservatively includes the river's maximum bank width;
        // pedestrian bridges are traversable land, but cannot be used as building plots.
        for(int i=1;i<IslandGeography.RIVER.size();i++)
            Engine.require(!IslandNavigation.intersects(IslandGeography.RIVER.get(i-1),IslandGeography.RIVER.get(i),footprint,7.1),"Choose a footprint clear of the river and its footbridges");
        Engine.require(s.buildings.values().stream().noneMatch(b->Math.hypot(b.x-x,b.z-z)<Math.max(13,b.outdoorRadius+7)),"Choose clear land away from existing buildings and public spaces");
        Engine.require(s.agents.values().stream().noneMatch(r->Math.hypot(r.x-x,r.z-z)<8),"Choose a site away from residents' current positions");
        Building obstacle=new Building();obstacle.x=x;obstacle.z=z;
        for(Resident r:s.agents.values())if(Travel.moving(r)) {
            Point from=new Point(r.x,r.z);
            for(double[] step:r.pending.route) {
                Point to=new Point(step[0],step[1]);
                Engine.require(!IslandNavigation.intersects(from,to,obstacle,5),"Choose a site clear of residents' accepted travel routes");from=to;
            }
        }
        WorldObjects.requireConstructionClear(s,x,z);
    }
    private static boolean atEntrance(State s,Resident a,Building b) {
        var door=Places.entrance(b);
        return !Places.enclosed(s.buildings.get(a.location))&&Math.hypot(a.x-door.x(),a.z-door.z())<=2;
    }
    private static boolean workHour(long minute) {int time=Math.floorMod(minute,1440);return time>=START_MINUTE&&time+60<=END_MINUTE;}
    public static String build(State s,Resident a,String target,String id,Engine.Effects e) {
        Engine.require(!a.paused&&a.pending==null,"Finish your current action before building");
        Engine.require(!Conversations.active(s,a),"End the current conversation before building");
        Site site=s.construction.get(target);Building b=s.buildings.get(target);
        Engine.require(site!=null&&b!=null&&"building".equals(site.status)&&"construction".equals(b.type),"Choose an active site from livelihood.construction.sites");
        Engine.require(!"self".equals(site.laborMode)||a.id.equals(site.sponsor),"A self-build site can only be worked by its sponsoring owner");
        Engine.require(atEntrance(s,a,b),"Move to the site's entranceTarget before contributing; the fenced interior stays closed");
        Engine.require(workHour(s.minute),"Choose a full hour of construction between 06:00 and 20:00");
        Engine.require(a.energy>=10&&a.hunger<95,"Eat or rest before building");
        Engine.require(site.completedHours+site.reservedHours<site.requiredHours,"All remaining work is already reserved by builders");
        Engine.require(s.accounts.getOrDefault(account(site.id),0L)>=cost(site),"The site cannot fund another hour");
        Engine.require(!s.accounts.containsKey("escrow:construction:"+id),"This construction reservation already exists");
        Engine.pay(s,account(site.id),"escrow:construction:"+id,cost(site),"construction_reservation",e);
        Pending p=Engine.pending(id,"BUILD_CONSTRUCTION",site.id,s.minute+60);p.startedAt=s.minute;p.lastWorkedMinute=s.minute;
        a.pending=p;site.reservedHours++;
        e.facts.add(new Engine.Fact("construction",a.id,site.id,a.name+" chose an hour of "+("self".equals(site.laborMode)?"self-building":"paid building")+" at "+b.name+"."));
        return "Construction accepted; "+site.materialCostPerHourCents+" cents of materials and "+site.hourlyWageCents+" cents of wages are reserved for one completed hour";
    }
    public static void tick(State s,Resident a,Engine.Effects e) {
        Pending p=a.pending;if(p==null||!"BUILD_CONSTRUCTION".equals(p.type))return;
        if(s.minute<=p.lastWorkedMinute)return;
        Site site=s.construction.get(p.target);Building b=s.buildings.get(p.target);
        String escrow="escrow:construction:"+p.id;
        int time=Math.floorMod(s.minute-1,1440);
        if(site==null||b==null||!"building".equals(site.status)||!atEntrance(s,a,b)||a.paused||a.energy<=0||a.hunger>=98
            ||s.minute!=p.lastWorkedMinute+1||time<START_MINUTE||time>=END_MINUTE||s.accounts.getOrDefault(escrow,0L)!=cost(site)) {
            long refund=s.accounts.getOrDefault(escrow,0L);
            if(refund>0)Engine.pay(s,escrow,account(p.target),refund,"construction_refund",e);
            s.accounts.remove(escrow);if(site!=null)site.reservedHours=Math.max(0,site.reservedHours-1);
            e.completions.add(new Engine.Completion(p.id,"cancelled","Construction interrupted; funds released and no hour credited"));a.pending=null;return;
        }
        a.energy=Math.max(0,a.energy-.1);p.workMinutes++;p.lastWorkedMinute=s.minute;
        if(p.workMinutes<60)return;
        if(site.hourlyWageCents>0)Engine.pay(s,escrow,a.id,site.hourlyWageCents,"construction_wage",e);
        Engine.pay(s,escrow,"city-supply",site.materialCostPerHourCents,"construction_materials",e);
        s.accounts.remove(escrow);site.reservedHours--;site.completedHours++;
        e.facts.add(new Engine.Fact("construction",a.id,site.id,a.name+" completed a paid building hour at "+b.name+"."));
        Engine.finish(a,e,"Completed a construction hour; paid "+site.hourlyWageCents+" cents");
        if(site.completedHours>=site.requiredHours)complete(s,site,b,e);
    }
    private static void complete(State s,Site site,Building b,Engine.Effects e) {
        site.status="completed";b.type=site.plannedType;b.plannedType=null;
        b.capacity=List.of("house","apartment").contains(b.type)?1:20;
        b.description="Completed through residents' actual on-site construction work.";
        if(List.of("house","apartment").contains(b.type)&&s.agents.containsKey(site.sponsor))s.agents.get(site.sponsor).home=b.id;
        if(List.of("bakery","fruit-shop").contains(b.type)){b.openMinute=480;b.closeMinute=1200;b.social=true;b.restAllowed=true;}
        long remaining=s.accounts.getOrDefault(account(site.id),0L);
        if(remaining>0)Engine.pay(s,account(site.id),"city".equals(site.sponsor)?"city-treasury":site.sponsor,remaining,"construction_capital_return",e);
        e.facts.add(new Engine.Fact("construction",site.sponsor,b.id,b.name+" is complete and available as a "+b.type+"."));
    }
}
