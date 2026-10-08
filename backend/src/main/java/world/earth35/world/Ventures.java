package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Resident-owned enterprises: consent, funded production, sales and employment in the actual world. */
public final class Ventures {
    private Ventures() {}
    public static final long REGISTRATION_FEE=5000, DEFAULT_CAPITAL=20000;
    private static final long MAX_TRANSFER=100_000_000;
    private static final Set<String> COMMERCIAL_WORKPLACES=Set.of("office","studio","market","workshop","coffee_shop","grocery","restaurant","shop","bakery","fruit-shop");
    public record SmallBusinessOption(String kind,String title,String productName,long unitMaterialCostCents,
                                      int productionMinutesPerUnit,long defaultPriceCents,long registrationFeeCents,
                                      long defaultCapitalCents) {}
    private static final List<SmallBusinessOption> SMALL_BUSINESSES=List.of(
        new SmallBusinessOption("bakery","Bakery","Fresh bread",600,30,1200,REGISTRATION_FEE,DEFAULT_CAPITAL),
        new SmallBusinessOption("fruit-shop","Fruit shop","Fruit basket",400,30,800,REGISTRATION_FEE,DEFAULT_CAPITAL));
    public static List<SmallBusinessOption> catalog() {return SMALL_BUSINESSES;}

    public static class Business {
        public String id,project,name,location,productId,jobId,loanId;
        public String kind="startup";
        public Set<String> owners=new LinkedHashSet<>();
        public int stock;
        public long priceCents,sales,revenueCents,contributedCents,materialsExpenseCents,distributedCents,createdMinute;
    }
    public record BusinessView(String id,String project,String name,String location,List<String> owners,
                               String productId,int stock,long priceCents,long sales,Long revenueCents,
                               Long balanceCents,String jobId,long createdMinute,Long contributedCents,
                               Long availableProfitCents,String kind,int employeeSeats) {}

    public static Business business(State s,String id) {
        Business b=s.businesses.get(id);
        Engine.require(b!=null,"Unknown resident business; use a business ID from innovations");
        return b;
    }
    private static Business owner(State s,Resident a,String id) {
        Business b=business(s,id);
        Engine.require(b.owners.contains(a.id),"Only an accepted co-owner can manage this business");
        return b;
    }
    private static void amount(Long value,String label) {
        Engine.require(value!=null&&value>0&&value<=MAX_TRANSFER,label+" must be between 1 and "+MAX_TRANSFER+" cents");
    }
    private static long profit(State s,Business b) {
        // Net cash above contributed capital is earned profit; wages already in escrow are deducted
        // from the account and the remaining scheduled payroll is reserved separately.
        return Math.max(0,s.accounts.getOrDefault(b.id,0L)-b.contributedCents-Economy.remainingPayroll(s,b.id));
    }
    public static List<BusinessView> views(State s,Resident viewer) {
        return s.businesses.values().stream().map(b->{
            boolean owns=viewer!=null&&b.owners.contains(viewer.id);
            return new BusinessView(b.id,b.project,b.name,b.location,b.owners.stream().sorted().toList(),b.productId,
                b.stock,b.priceCents,b.sales,owns?b.revenueCents:null,owns?s.accounts.getOrDefault(b.id,0L):null,
                b.jobId,b.createdMinute,owns?b.contributedCents:null,owns?profit(s,b):null,b.kind,
                s.jobs.containsKey(b.jobId)?Math.max(0,s.jobs.get(b.jobId).capacity):0);
        }).toList();
    }
    private static Building workplace(State s,Resident a,String location,int minutes) {
        Building b=s.buildings.get(location);
        Engine.require(b!=null&&Places.enclosed(b)&&COMMERCIAL_WORKPLACES.contains(b.type),
            "Choose an open public commercial workplace for this business");
        Engine.at(a,location);Places.requireOpen(b,s.minute);
        Engine.require(minutes>=0&&minutes<=300,"Production must take at most 300 minutes");
        for(int i=0;i<minutes;i++)Engine.require(Places.open(b,s.minute+i),"The workplace must stay open for the whole requested activity");
        return b;
    }
    private static Job job(String id,String businessName,Building workplace,int seats,String account) {
        Job job=new Job(id,businessName+" team member",workplace.id,seats);
        job.hourlyWage=1000;job.employerAccount=account;job.workDays=new ArrayList<>(workplace.openDays);
        if(workplace.openMinute<workplace.closeMinute) {
            job.startMinute=Math.max(540,workplace.openMinute);
            job.endMinute=Math.min(1020,workplace.closeMinute);
            if(job.endMinute-job.startMinute<60) {
                job.startMinute=workplace.openMinute;job.endMinute=workplace.closeMinute;
            }
        }
        Engine.require(job.endMinute-job.startMinute>=60&&!job.workDays.isEmpty(),"Workplace must permit a full working hour");
        job.earlyAccessMinutes=0;job.lateAccessMinutes=0;job.outsideHoursWorkAllowed=false;
        return job;
    }
    /** Familiar sole-owner shops do not need an invented research project or consenting co-founders. */
    public static String startSmallBusiness(State s,Resident a,String location,String kind,String name,Integer capacity,
                                            Long capital,String actionId,Engine.Effects effects) {
        return startSmallBusiness(s,a,location,kind,name,capacity,capital,null,actionId,effects);
    }
    public static String startSmallBusiness(State s,Resident a,String location,String kind,String name,Integer capacity,
                                            Long capital,Integer loanYears,String actionId,Engine.Effects effects) {
        Engine.require(!a.paused&&a.pending==null,"Finish your current activity before registering a business");
        Engine.require(!Conversations.active(s,a),"End your conversation before registering a business");
        SmallBusinessOption option=SMALL_BUSINESSES.stream().filter(o->o.kind().equals(kind)).findFirst()
            .orElseThrow(()->new Engine.Invalid("Choose a small-business kind from livelihood.smallBusinesses"));
        Building host=workplace(s,a,location,0);
        String businessName=name==null?"":name.strip();
        Engine.require(businessName.length()>=3&&businessName.length()<=100,"Provide a business name of 3 to 100 characters");
        int seats=capacity==null?0:capacity;
        Engine.require(seats>=0&&seats<=5,"Choose zero to five employee seats; zero lets you run the business yourself");
        long investment=capital==null?DEFAULT_CAPITAL:capital;amount(investment,"Initial capital");
        String id="business:"+actionId,jobId="job:"+id,productId="product:"+id;
        Engine.require(!s.businesses.containsKey(id)&&!s.accounts.containsKey(id)&&!s.jobs.containsKey(jobId)&&!s.products.containsKey(productId),
            "Business action ID already exists");
        Job job=job(jobId,businessName,host,seats,id);
        long payroll=job.hourlyWage*seats*((job.endMinute-job.startMinute)/60);
        Engine.require(investment>=option.unitMaterialCostCents()+payroll,
            "Initial capital must fund one stock unit plus a working day's wages for the chosen employee seats; choose fewer seats or contribute more");
        Business b=new Business();b.id=id;b.project="";b.kind=option.kind();b.name=businessName;b.location=location;
        b.productId=productId;b.jobId=jobId;b.owners.add(a.id);b.contributedCents=investment;
        b.priceCents=option.defaultPriceCents();b.createdMinute=s.minute;
        Projects.Product product=new Projects.Product();product.id=productId;product.project="";product.title=option.productName();
        product.subject="business";product.recipe="nourishment";product.unitCost=option.unitMaterialCostCents();product.createdMinute=s.minute;
        product.description=option.productName()+" prepared by "+businessName+" using paid ingredients and actual onsite work.";
        product.effects=Projects.recipe(product.recipe).effects();
        b.loanId=Loans.finance(s,a,investment+REGISTRATION_FEE,loanYears,id,actionId,effects);
        Engine.pay(s,a.id,"city-treasury",REGISTRATION_FEE,"business_registration:"+id,effects);
        Engine.pay(s,a.id,id,investment,"business_capital",effects);
        s.businesses.put(id,b);s.products.put(productId,product);s.jobs.put(jobId,job);
        effects.facts.add(new Engine.Fact("business",a.id,id,a.name+" chose to open "+businessName+", a resident-owned "+option.title().toLowerCase(Locale.ROOT)+" at "+host.name+". Stock starts at zero and income requires actual sales."));
        return "Opened "+id+"; product "+productId+" requires "+option.productionMinutesPerUnit()+" onsite minutes and "+option.unitMaterialCostCents()+" cents of ingredients per unit via PRODUCE_PRODUCT. "+
            (seats==0?"You can run it yourself without salaried employee seats.":"Job "+jobId+" offers "+seats+" seat(s) funded only by this business.")+" Sell actual stock and WITHDRAW_PROFIT to support your livelihood"+
            (b.loanId==null?"":"; shortfall financed by "+b.loanId);
    }
    public static String endorse(State s,Resident a,String projectId,Engine.Effects effects) {
        Projects.Project p=Projects.member(s,a,projectId);
        Engine.require(p.businessId==null||p.businessId.isBlank(),"This project already has a business");
        Engine.require(p.businessEndorsements.add(a.id),"Your endorsement is already recorded");
        effects.facts.add(new Engine.Fact("business",a.id,p.id,a.name+" explicitly agreed to equal co-ownership of a business from "+p.title+"."));
        return "Business endorsement recorded: every current project member must separately agree to equal co-ownership. The founder must fund the 5000-cent registration fee and chosen capital; other owners fund it only by their own choice";
    }
    public static String launch(State s,Resident a,String projectId,String name,Integer capacity,Long capital,
                                String actionId,Engine.Effects effects) {
        return launch(s,a,projectId,name,capacity,capital,null,actionId,effects);
    }
    public static String launch(State s,Resident a,String projectId,String name,Integer capacity,Long capital,Integer loanYears,
                                String actionId,Engine.Effects effects) {
        Projects.Project p=Projects.member(s,a,projectId);
        Engine.require(a.id.equals(p.founder),"Only the project founder can launch its business");
        Engine.require(p.businessId==null||p.businessId.isBlank(),"This project already has a business");
        Engine.require(Projects.tested(p),"Build a prototype and complete measured test research before launching");
        Projects.Product product=Projects.productFor(s,p);
        Engine.require(product!=null,"The tested project needs a built product");
        Projects.requireWorkplace(s,a,p.location,0);
        Engine.require(p.members.contains(a.id)&&!p.members.isEmpty()&&p.members.size()<=5,
            "The business needs one to five accepted project members");
        Engine.require(p.businessEndorsements.containsAll(p.members),
            "Every current project member must independently ENDORSE_BUSINESS before launch");
        Engine.require(p.members.stream().allMatch(s.agents::containsKey),"All accepted co-owners must still exist in the world");
        String businessName=name==null?"":name.strip();
        Engine.require(businessName.length()>=3&&businessName.length()<=100,"Provide a business name of 3 to 100 characters");
        int seats=capacity==null?1:capacity;
        Engine.require(seats>=1&&seats<=5,"Choose job capacity from one to five");
        long investment=capital==null?DEFAULT_CAPITAL:capital;
        amount(investment,"Initial capital");
        String id="business:"+actionId,jobId="job:"+id;
        Engine.require(!s.businesses.containsKey(id)&&!s.accounts.containsKey(id)&&!s.jobs.containsKey(jobId),"Business action ID already exists");
        Business b=new Business();b.id=id;b.project=p.id;b.name=businessName;b.location=p.location;
        b.productId=product.id;b.jobId=jobId;b.owners.addAll(p.members);b.contributedCents=investment;
        b.priceCents=Math.min(100000,Math.max(1,product.unitCost*2));b.createdMinute=s.minute;
        Building workplace=s.buildings.get(p.location);
        Job job=job(jobId,businessName,workplace,seats,id);
        long payroll=job.hourlyWage*seats*((job.endMinute-job.startMinute)/60);
        Engine.require(investment>=product.unitCost+payroll,"Initial capital must fund one stock unit and a working day's wages for every employee seat");
        b.loanId=Loans.finance(s,a,investment+REGISTRATION_FEE,loanYears,id,actionId,effects);
        // Startup money stays separate from the host workplace; daily city operating support cannot reach it.
        Engine.pay(s,a.id,"city-treasury",REGISTRATION_FEE,"business_registration:"+id,effects);
        Engine.pay(s,a.id,id,investment,"business_capital",effects);
        s.businesses.put(id,b);s.jobs.put(jobId,job);p.businessId=id;p.status="launched";
        effects.facts.add(new Engine.Fact("business",a.id,id,a.name+" launched "+businessName+" with "+b.owners.size()+" consenting co-owner(s) at "+workplace.name+". Manufactured stock starts at zero."));
        return "Launched "+id+" at "+p.location+"; job "+jobId+" pays 1000 cents/hour from business funds. Produce actual stock before selling"+
            (b.loanId==null?"":"; shortfall financed by "+b.loanId);
    }
    public static String fund(State s,Resident a,String businessId,Long cents,Engine.Effects effects) {
        Business b=owner(s,a,businessId);amount(cents,"Funding");
        Engine.require(a.money>=cents,"Insufficient personal funds to fund the business");
        Engine.require(b.contributedCents<=Long.MAX_VALUE-cents,"Business contribution limit reached");
        Engine.pay(s,a.id,b.id,cents,"business_funding",effects);b.contributedCents+=cents;
        effects.facts.add(new Engine.Fact("business",a.id,b.id,a.name+" contributed personal funds to "+b.name+"."));
        return "Business funded with "+cents+" cents of your actual money";
    }
    public static String price(State s,Resident a,String businessId,String productId,Long cents,Engine.Effects effects) {
        Business b=owner(s,a,businessId);
        Engine.require(b.productId.equals(productId),"Choose this business's manufactured product ID");
        Engine.require(cents!=null&&cents>=1&&cents<=100000,"Product price must be 1 to 100000 cents");
        b.priceCents=cents;
        effects.facts.add(new Engine.Fact("business",a.id,b.id,b.name+" set its product price to "+cents+" cents per unit."));
        return "Product price set to "+cents+" cents per unit; demand depends on actual buyers";
    }
    public static String produce(State s,Resident a,String businessId,Integer requested,String actionId,Engine.Effects effects) {
        Business b=business(s,businessId);Job job=s.jobs.get(a.job);
        Engine.require(b.owners.contains(a.id)||job!=null&&b.id.equals(Economy.employer(job)),
            "Only a co-owner or accepted business employee can manufacture its stock");
        Engine.require(!a.paused&&a.pending==null,"Finish your current activity before manufacturing");
        Engine.require(!Conversations.active(s,a),"End your conversation before manufacturing");
        Engine.require(a.energy>=5&&a.hunger<95,"Eat or rest before manufacturing");
        int quantity=requested==null?1:requested;
        Engine.require(quantity>=1&&quantity<=10,"Produce one to ten units per batch");
        Projects.Product product=s.products.get(b.productId);
        Engine.require(product!=null&&product.unitCost>0&&product.unitCost<=MAX_TRANSFER/quantity,"Business product has no valid material cost");
        workplace(s,a,b.location,quantity*30);
        long cost=product.unitCost*quantity;
        Engine.require(s.accounts.getOrDefault(b.id,0L)-Economy.remainingPayroll(s,b.id)>=cost,
            "Business needs funded materials while preserving its remaining scheduled wages; fund it or produce after the shift");
        Engine.require(b.stock<=Integer.MAX_VALUE-quantity,"Business stock limit reached");
        String escrow="escrow:manufacture:"+actionId;
        Engine.require(!s.accounts.containsKey(escrow),"Production action ID already has reserved materials");
        Engine.pay(s,b.id,escrow,cost,"production_material_reservation",effects);
        Pending p=Engine.pending(actionId,"PRODUCE_PRODUCT",b.id,s.minute+quantity*30L);
        p.startedAt=s.minute;p.projectMethod=Integer.toString(quantity);p.reservedCost=cost;p.workMinutes=0;a.pending=p;
        effects.facts.add(new Engine.Fact("production",a.id,b.id,a.name+" started manufacturing "+quantity+" unit(s) for "+b.name+"; every unit requires 30 minutes of onsite work."));
        return "Production accepted: "+quantity+" units, "+(quantity*30)+" onsite minutes and "+cost+" cents reserved materials";
    }
    public static boolean tick(State s,Resident a,Engine.Effects effects) {
        Pending p=a.pending;
        if(p==null||!"PRODUCE_PRODUCT".equals(p.type))return false;
        Business b=s.businesses.get(p.target);
        Job job=s.jobs.get(a.job);
        boolean authorized=b!=null&&(b.owners.contains(a.id)||job!=null&&b.id.equals(Economy.employer(job)));
        if(!authorized||a.paused||a.energy<=0||a.hunger>=98||!b.location.equals(a.location)||!Places.open(s.buildings.get(b.location),s.minute-1)) {
            interrupt(s,a,effects,"Production interrupted; onsite work at the open workplace is required");return true;
        }
        int quantity;
        try {quantity=Integer.parseInt(p.projectMethod);}catch(RuntimeException ex) {
            interrupt(s,a,effects,"Production interrupted because its saved batch is invalid");return true;
        }
        if(quantity<1||quantity>10||s.accounts.getOrDefault("escrow:manufacture:"+p.id,0L)!=p.reservedCost) {
            interrupt(s,a,effects,"Production interrupted because its reserved materials are unavailable");return true;
        }
        // Engine advances one minute before ticking; validate the minute just worked. A batch
        // finishing at closing time is valid, and duplicate ticks at one minute cannot add work.
        if(s.minute<=p.startedAt+p.workMinutes)return true;
        a.energy=Math.max(0,a.energy-.08);p.workMinutes++;
        if(p.workMinutes>=quantity*30) {
            if(b.stock>Integer.MAX_VALUE-quantity) {
                interrupt(s,a,effects,"Production interrupted because the business stock limit was reached");return true;
            }
            Engine.pay(s,"escrow:manufacture:"+p.id,"city-supply",p.reservedCost,"production_materials:"+b.productId,effects);
            s.accounts.remove("escrow:manufacture:"+p.id);b.materialsExpenseCents+=p.reservedCost;b.stock+=quantity;
            effects.facts.add(new Engine.Fact("production",a.id,b.id,a.name+" manufactured "+quantity+" usable unit(s) for "+b.name+"; stock is now "+b.stock+"."));
            Engine.finish(a,effects,"Manufactured "+quantity+" units into business stock");
        }
        return true;
    }
    public static void interrupt(State s,Resident a,Engine.Effects effects,String reason) {
        Pending p=a.pending;if(p==null||!"PRODUCE_PRODUCT".equals(p.type))return;
        String escrow="escrow:manufacture:"+p.id;long refund=s.accounts.getOrDefault(escrow,0L);
        if(refund>0)Engine.pay(s,escrow,p.target,refund,"production_material_refund",effects);
        s.accounts.remove(escrow);
        effects.completions.add(new Engine.Completion(p.id,"cancelled",reason+"; no finished batch was credited and unspent materials were refunded"));a.pending=null;
    }
    public static String buy(State s,Resident a,String businessId,String productId,Integer requested,Engine.Effects effects) {
        Business b=business(s,businessId);Engine.at(a,b.location);
        Places.requireOpen(s.buildings.get(b.location),s.minute);
        Engine.require(b.productId.equals(productId)&&s.products.containsKey(productId),"Choose this business's product ID");
        int quantity=requested==null?1:requested;
        Engine.require(quantity>=1&&quantity<=100,"Buy one to 100 units");
        Engine.require(b.stock>=quantity,"Business is out of manufactured stock");
        Engine.require(b.priceCents>=1&&b.priceCents<=100000,"Business has no valid product price");
        long cost=b.priceCents*quantity;
        Engine.require(a.money>=cost,"Insufficient funds for this product purchase");
        int owned=a.inventory.getOrDefault(productId,0);
        Engine.require(owned<=Integer.MAX_VALUE-quantity,"Inventory limit reached");
        Engine.require(b.revenueCents<=Long.MAX_VALUE-cost&&b.sales<=Long.MAX_VALUE-quantity,"Business sales limit reached");
        Engine.pay(s,a.id,b.id,cost,"product_purchase:"+productId,effects);
        a.inventory.put(productId,owned+quantity);b.stock-=quantity;b.sales+=quantity;b.revenueCents+=cost;
        effects.facts.add(new Engine.Fact("economy",a.id,b.id,a.name+" bought "+quantity+" unit(s) of "+s.products.get(productId).title+" from "+b.name+"."));
        return "Bought "+quantity+" usable units for "+cost+" cents; USE_PRODUCT with "+productId+" to use one";
    }
    public static String withdraw(State s,Resident a,String businessId,Long cents,Engine.Effects effects) {
        Business b=owner(s,a,businessId);amount(cents,"Profit distribution");
        Engine.require(!b.owners.isEmpty()&&b.owners.stream().allMatch(s.agents::containsKey),"Every accepted co-owner must still exist");
        Engine.require(cents%b.owners.size()==0,"Choose a total divisible by the co-owner count so every owner receives an equal whole-cent share");
        Engine.require(profit(s,b)>=cents,"Distribution exceeds earned profit after preserving contributed capital and remaining scheduled wages");
        long share=cents/b.owners.size();
        for(String id:b.owners.stream().sorted().toList())Engine.pay(s,b.id,id,share,"business_profit_distribution",effects);
        b.distributedCents+=cents;
        effects.facts.add(new Engine.Fact("business",a.id,b.id,b.name+" distributed earned profit equally to all "+b.owners.size()+" co-owner(s)."));
        return "Distributed "+cents+" cents: "+share+" cents to every accepted co-owner";
    }
}
