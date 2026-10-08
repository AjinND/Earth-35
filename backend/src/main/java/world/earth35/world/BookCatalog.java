package world.earth35.world;

import java.util.*;

/** Original educational lessons, ordered from foundations through applied and advanced study. */
public final class BookCatalog {
    private BookCatalog() {}

    public record Book(String id,String title,String subject,String depth,String description,
                       List<String> lessons,int lessonMinutes,int prerequisitePoints) {
        public Book {
            Objects.requireNonNull(id); Objects.requireNonNull(title); Objects.requireNonNull(subject);
            Objects.requireNonNull(depth); Objects.requireNonNull(description); Objects.requireNonNull(lessons);
            if(!id.matches("[a-z0-9]+(?:-[a-z0-9]+)*")||title.isBlank()||subject.isBlank()||description.isBlank())
                throw new IllegalArgumentException("Books need a stable ID and descriptive metadata");
            lessons=List.copyOf(lessons);
            if(lessons.size()<4||lessons.stream().anyMatch(String::isBlank)||new HashSet<>(lessons).size()!=lessons.size())
                throw new IllegalArgumentException("Books need at least four distinct substantive lessons");
            int expectedMinutes=switch(depth) { case "introductory"->30; case "intermediate"->40; case "advanced"->60;
                default->throw new IllegalArgumentException("Unknown book depth: "+depth); };
            int expectedPrerequisite=switch(depth) { case "introductory"->0; case "intermediate"->12; default->28; };
            if(lessonMinutes!=expectedMinutes||prerequisitePoints!=expectedPrerequisite)
                throw new IllegalArgumentException("Study duration and prerequisites must match depth");
        }
    }

    private static final List<Book> BOOKS=create();
    public static List<Book> books() { return BOOKS; }

    private static void intro(List<Book> books,String subject,String id,String title,String description,String... lessons) {
        books.add(new Book(id,title,subject,"introductory",description,List.of(lessons),30,0));
    }
    private static void intermediate(List<Book> books,String subject,String id,String title,String description,String... lessons) {
        books.add(new Book(id,title,subject,"intermediate",description,List.of(lessons),40,12));
    }
    private static void advanced(List<Book> books,String subject,String id,String title,String description,String... lessons) {
        books.add(new Book(id,title,subject,"advanced",description,List.of(lessons),60,28));
    }

    private static List<Book> create() {
        List<Book> books=new ArrayList<>();
        intro(books,"gardening","garden-basics","A Small Garden","Plant care, light, water and compost.",
            "Plant roots take up water and mineral nutrients from soil.","Plants use light in photosynthesis to make sugars.",
            "Drainage helps prevent soil from remaining waterlogged.","Compost is decomposed organic matter that can improve soil.");
        intro(books,"gardening","soil-and-seasons","Soil and Seasons","Soil, moisture and the changing seasons.",
            "Soil texture affects how water drains and is retained.","Mulch can reduce evaporation from soil.",
            "Rotating crops can interrupt some pest and disease cycles.","Watering needs depend on plants, soil and weather.");
        intermediate(books,"gardening","garden-pest-ecology","Garden Pest Ecology","Identify damage, support beneficial organisms and compare interventions.",
            "Inspect leaf undersides and damage patterns before deciding whether insects, disease or water stress caused a symptom.",
            "An action threshold balances expected crop loss against the cost and ecological effects of pest control.",
            "Flowering habitat can support predators, but monitoring is needed to see whether pest numbers actually fall.",
            "Alternating control methods and removing infected debris can reduce repeated pest pressure and resistance selection.");
        intermediate(books,"gardening","garden-water-planning","Water for Growing","Calculate irrigation needs and design efficient planting schedules.",
            "Estimate irrigation demand from evapotranspiration minus effective rainfall, then account for application efficiency.",
            "Drip irrigation delivers water near roots; spacing and run time should match soil infiltration and rooting depth.",
            "Measure soil moisture at several depths because a damp surface does not guarantee water in the root zone.",
            "Group plants with similar water needs so irrigation schedules do not overwater one crop to satisfy another.");
        advanced(books,"gardening","garden-regenerative-design","Regenerative Garden Design","Use nutrient budgets and seasonal trials to maintain productive soil.",
            "A nutrient budget compares inputs, crop removal and losses to identify depletion or harmful accumulation.",
            "Cover-crop mixtures can combine nitrogen fixation, erosion protection and deep rooting, with tradeoffs in termination timing.",
            "Compare garden treatments in replicated plots and track yields across seasons before attributing improvement to a practice.",
            "Design rotations around crop families, harvest timing and soil cover to reduce disease while keeping beds productive.");

        intro(books,"science","science-everyday","Everyday Science","Observation, experiments and energy.",
            "An observation records what is noticed; an inference interprets it.","A fair comparison controls variables other than the one being tested.",
            "Energy can be transferred and transformed between forms.","Repeated observations help distinguish a pattern from a coincidence.");
        intro(books,"science","living-systems","Living Systems","Organisms, ecosystems and food webs.",
            "An ecosystem includes organisms and their physical environment.","Producers use an energy source to make organic matter.",
            "Food webs describe feeding relationships among organisms.","Changes to one population can affect other parts of an ecosystem.");
        intermediate(books,"science","science-measurement-models","Measurements and Models","Connect measurable quantities, units and predictions.",
            "Calibrate an instrument against a reference to distinguish a systematic offset from random measurement variation.",
            "Dimensional analysis rejects equations whose terms have incompatible units and helps detect calculation errors.",
            "A model simplifies a system; state its assumptions and compare its predictions with measurements outside the fitting data.",
            "Change one model parameter at a time to determine which uncertainties most affect a prediction.");
        intermediate(books,"science","science-evidence-uncertainty","Evidence and Uncertainty","Interpret noisy results and distinguish association from cause.",
            "A large sample can reduce sampling noise while leaving bias from poor selection or faulty measurement intact.",
            "Report an estimate with its uncertainty interval because nearby values may also be compatible with the observations.",
            "A shared cause can create a correlation between two variables even when neither directly affects the other.",
            "An unsuccessful replication can reveal hidden conditions, weak effects or procedural differences that deserve investigation.");
        advanced(books,"science","science-research-design","Research Under Constraints","Plan causal investigations and revise explanations using competing predictions.",
            "Random assignment makes treatment groups comparable on average; blinding limits behavior and measurement changes caused by expectations.",
            "A factorial experiment varies several factors together to detect interactions that isolated comparisons can miss.",
            "Predefine outcomes and analysis choices so selecting only favorable results does not distort the evidence.",
            "Compare rival hypotheses through predictions on which they disagree, then update confidence when new evidence arrives.");

        intro(books,"history","reading-history","Reading the Past","Sources, chronology and historical evidence.",
            "Chronology orders events and helps establish their sequence.","Primary sources come from the period or experience being studied.",
            "Accounts of the same event can reflect different perspectives.","Corroborating independent sources strengthens a historical explanation.");
        intro(books,"history","city-histories","How Cities Change","Places, institutions and the evidence of urban change.",
            "Transport routes and resources can influence where settlements grow.","Institutions organize shared services and civic decisions.",
            "Buildings, maps and records can provide evidence of change over time.","A local legend should be distinguished from a documented historical claim.");
        intermediate(books,"history","history-trade-empires","Trade, Power and Empire","Investigate exchange networks, administration and unequal power.",
            "Trade routes move goods, ideas and diseases; reconstructing them requires comparing material finds with written records.",
            "Tax systems and transport costs influence how a government can support distant military and administrative institutions.",
            "Colonial records often privilege officials' viewpoints, so oral histories and local archives can reveal excluded experiences.",
            "A political border may change rapidly while languages, trade relationships and everyday institutions persist.");
        intermediate(books,"history","history-industry-society","Industry and Social Change","Trace how technology, labor and political organization interact.",
            "Industrial production changes demand for labor and resources, but its effects depend on wages, ownership and regulation.",
            "Population and price records can test broad claims about living standards that individual memoirs alone cannot establish.",
            "Social movements combine grievances, organization and opportunities; similar grievances do not always produce similar outcomes.",
            "Compare changes across regions to distinguish a widespread trend from the effects of a local policy or event.");
        advanced(books,"history","history-causal-interpretation","Explaining Historical Change","Build bounded causal arguments from uneven and incomplete evidence.",
            "Separate long-term conditions, immediate triggers and mechanisms when explaining why a historical event occurred.",
            "Source survival is selective; an absent record may reflect loss or exclusion rather than an absent activity.",
            "A historical counterfactual should change one plausible condition and preserve the constraints people faced at the time.",
            "Assess an explanation against rival accounts, contradictory evidence and the risk of judging past actors by later knowledge.");

        intro(books,"communication","listening-well","Listening Well","Questions, understanding and thoughtful disagreement.",
            "Open questions invite someone to explain their perspective.","Paraphrasing and checking with the speaker can clarify understanding.",
            "Separating observations from assumptions helps avoid misunderstandings.","Specific, respectful disagreement can keep a discussion constructive.");
        intro(books,"communication","telling-stories","Telling Stories","Setting, characters and clear narrative.",
            "A setting gives a story a place and context.","A character's goals and obstacles can shape a narrative.",
            "Cause and effect connect events into a coherent story.","Concrete details can make a scene easier to imagine.");
        intermediate(books,"communication","communication-negotiation","Negotiating Shared Solutions","Clarify interests, alternatives and workable agreements.",
            "A stated position names a demand; an underlying interest explains the need that several different agreements might satisfy.",
            "Evaluate a proposed agreement against the best realistic alternative if no agreement is reached.",
            "Separate options from commitments so participants can explore trades without treating each suggestion as a final offer.",
            "Record responsibilities, deadlines and a review process because vague agreement can hide incompatible expectations.");
        intermediate(books,"communication","communication-technical-writing","Writing to Explain","Make complex instructions and evidence understandable to a specific audience.",
            "Begin with the reader's decision or task, then include the context needed to act correctly.",
            "Define an unfamiliar term when first used and keep the same name for the same concept throughout an explanation.",
            "Test instructions with a new reader to reveal missing prerequisites and steps the author takes for granted.",
            "Distinguish measured facts, interpretations and recommendations so readers can assess the strength of a claim.");
        advanced(books,"communication","communication-group-deliberation","Deliberation in Difficult Groups","Structure collective reasoning when incentives and interpretations differ.",
            "Collect independent judgments before discussion to reduce anchoring on the first confident speaker.",
            "Map the premises behind a disagreement to identify whether evidence, values or definitions need attention.",
            "Use an explicit decision rule and a recorded rationale so participation does not falsely imply unanimous agreement.",
            "Invite dissenting explanations and check whether status differences prevent important information from reaching the group.");

        intro(books,"technology","technology-tools-energy","Tools and Energy","Understand how common devices transform energy and extend human work.",
            "A lever trades movement distance for force; its mechanical advantage depends on distances from the pivot.",
            "A battery stores chemical energy and supplies electrical energy, while its capacity limits operating time.",
            "Efficiency compares useful output with input; wasted energy often appears as heat, sound or unwanted movement.",
            "A device's operating requirements should match its power supply, environment and the task it must perform.");
        intro(books,"technology","technology-networks","Connected Devices","Follow information through sensors, links and everyday digital systems.",
            "A sensor converts a physical quantity into a signal that a system can measure and interpret.",
            "A communication protocol specifies how devices format messages and coordinate their exchange.",
            "Bandwidth limits data transfer rate, while latency measures how long a message takes to arrive.",
            "Authentication establishes a claimed identity; authorization controls which operations that identity may perform.");
        intermediate(books,"technology","technology-electronic-prototypes","Electronic Prototypes","Build small sensor systems and diagnose their limitations.",
            "Use voltage, current and resistance together when selecting components so ratings are not exceeded.",
            "Sampling too slowly can make a changing signal appear to have a different frequency or miss short events.",
            "Filter sensor noise carefully because stronger smoothing also delays detection of real changes.",
            "Test a prototype with expected loads and environmental variation rather than relying on one successful demonstration.");
        intermediate(books,"technology","technology-reliable-systems","Reliable Technology","Maintain connected equipment through redundancy and observable failure handling.",
            "A backup only reduces failure risk when it avoids the same power, location or control failure as the primary system.",
            "Monitor both equipment status and user-visible outcomes because a running component may still deliver the wrong service.",
            "Preventive maintenance intervals should reflect wear, failure consequences and evidence from operating records.",
            "Design graceful degradation so a nonessential failure preserves the essential function in a limited form.");
        advanced(books,"technology","technology-lifecycle-design","Technology Across Its Life","Evaluate a technological product through adoption, repair and retirement.",
            "Lifecycle analysis compares resource use and impacts from extraction through manufacture, operation and disposal.",
            "Interoperable interfaces and replaceable parts can reduce switching costs, repair expense and premature obsolescence.",
            "Threat modeling traces valuable assets, access paths and plausible misuse before choosing protective controls.",
            "A deployment pilot should measure usefulness, failure burden and unequal access before committing to wider adoption.");

        intro(books,"computing","computing-programming-foundations","Instructions That Run","Model problems with variables, conditions, loops and small functions.",
            "A variable associates a name with a value; reassignment changes that value and may affect later instructions.",
            "A conditional chooses a branch from a Boolean condition, so boundary cases should be checked explicitly.",
            "A loop repeats work while its continuation condition holds; progress toward termination must be identifiable.",
            "A function defines inputs and an output so a larger problem can be decomposed into testable parts.");
        intro(books,"computing","computing-data-records","Data and Records","Represent facts accurately and retrieve them from structured collections.",
            "A data type determines which values and operations are valid, such as numbers for arithmetic and text for labels.",
            "A stable identifier distinguishes records even when names or other descriptive fields change.",
            "A table stores records in rows and attributes in columns; a key helps identify each record uniquely.",
            "Validate required fields and allowed ranges at entry to prevent invalid records from spreading through a system.");
        intermediate(books,"computing","computing-algorithms-structures","Algorithms and Data Structures","Choose representations and estimate how work grows with input size.",
            "A hash map supports expected constant-time lookup, while sorted data can support range queries and ordered traversal.",
            "Binary search halves a sorted search interval each step, producing logarithmic growth in comparisons.",
            "Breadth-first search visits a graph by distance in edges and can find shortest paths in an unweighted graph.",
            "An invariant states what remains true during an algorithm and helps explain why its final result is correct.");
        intermediate(books,"computing","computing-application-design","Applications with Clear Contracts","Organize software around boundaries, tests and durable data changes.",
            "An interface specifies accepted inputs, outputs and errors so callers need not depend on internal implementation details.",
            "A database transaction groups related changes so failure does not leave only part of an operation committed.",
            "Test observable behavior with normal, boundary and invalid inputs rather than duplicating the implementation's steps.",
            "Separate domain rules from display and storage concerns to change one without silently changing the others.");
        advanced(books,"computing","computing-distributed-systems","Distributed Systems in Practice","Reason about coordination, retries and partial failure across machines.",
            "A network timeout cannot distinguish a failed request from a completed request whose response was lost.",
            "An idempotency key lets a server recognize repeated requests and avoid applying the same operation twice.",
            "Concurrent updates require an explicit conflict strategy such as version checks, serialization or a defined merge rule.",
            "Consensus coordinates agreement among replicas under stated failure assumptions, with latency and availability tradeoffs.");

        intro(books,"business","business-customers-value","Customers and Value","Find concrete customer problems and test whether a proposed offer helps.",
            "A customer segment groups people with similar needs and buying circumstances, not merely similar demographics.",
            "Ask about recent behavior and existing workarounds because stated enthusiasm does not establish willingness to buy.",
            "A value proposition connects a specific customer problem to a benefit the offer can deliver.",
            "A small paid trial can test demand before investing in facilities, inventory or a full product.");
        intro(books,"business","business-small-operations","Running a Small Enterprise","Coordinate orders, inventory and cash while serving customers reliably.",
            "Revenue is money earned from sales; profit subtracts the costs of producing and operating the business.",
            "Cash received and profit earned can occur at different times, so a profitable business can still run out of cash.",
            "Reorder points combine expected demand during supplier lead time with a buffer for uncertainty.",
            "A clear service promise helps align staffing, stock and response time with what customers expect.");
        intermediate(books,"business","business-venture-economics","Testing Venture Economics","Estimate contribution, capacity and the assumptions that determine viability.",
            "Contribution per unit is price minus variable cost; fixed costs divided by contribution give break-even volume.",
            "Customer acquisition cost should be compared with retained contribution over the customer relationship, not just initial revenue.",
            "The slowest constrained operation limits system throughput; expanding an unconstrained step may not increase output.",
            "Sensitivity analysis changes uncertain prices, demand and costs to reveal which assumptions threaten a business plan.");
        intermediate(books,"business","business-teams-processes","Teams and Operating Processes","Turn strategy into responsibilities, feedback and repeatable work.",
            "Assign decision rights and ownership explicitly so a shared task does not become a task nobody completes.",
            "A process measure should connect to customer outcomes because optimizing an internal count can worsen service.",
            "Standardize routine steps while defining an escalation path for exceptions that the routine cannot handle.",
            "Review mistakes for contributing conditions and incentives, then test whether a process change reduces recurrence.");
        advanced(books,"business","business-strategy-innovation","Strategy and New Ventures","Design an enterprise around advantage, uncertainty and staged investment.",
            "A competitive advantage must create value and be difficult for rivals to copy or substitute at acceptable cost.",
            "A business model links customers, delivery activities, partners and revenue so one change can affect several assumptions.",
            "Stage investment around evidence milestones to preserve the option to expand, revise or stop a venture.",
            "A new product concept needs an adoption mechanism and operating economics as well as a technically possible invention.");

        intro(books,"arts","arts-looking-drawing","Looking and Drawing","Develop observation through shape, proportion and light.",
            "Draw large shapes and their proportions before details to keep the overall structure coherent.",
            "Negative space is the shape around an object and can reveal proportion errors that focused object drawing misses.",
            "Value describes lightness and darkness; a consistent light source organizes shadows and perceived volume.",
            "Linear perspective represents parallel lines converging toward vanishing points as their distance increases.");
        intro(books,"arts","arts-color-composition","Color and Composition","Arrange visual elements to guide attention and establish relationships.",
            "Hue, saturation and value describe different aspects of color and should be adjusted separately when diagnosing a mismatch.",
            "Contrast draws attention, so competing strong contrasts can obscure an intended focal point.",
            "Repeated shapes create rhythm while variation prevents repetition from making every element equally predictable.",
            "A limited palette can create unity, but adjacent colors and lighting still change how each color appears.");
        intermediate(books,"arts","arts-materials-making","Materials and Making","Choose processes for prints, sculpture and mixed-media work.",
            "Relief printing transfers raised surfaces, while intaglio printing transfers ink held in recessed marks.",
            "Sculpture requires a stable load path and center of mass as well as a compelling form.",
            "Material tests reveal how moisture, adhesion and curing change a mixed-media work before final assembly.",
            "An edition needs consistent registration and documented process settings to reproduce an intentional visual result.");
        intermediate(books,"arts","arts-context-interpretation","Art in Context","Interpret artworks through form, patronage and audience.",
            "Compare composition and material choices with an artwork's commission, location and intended audience.",
            "A symbol can carry different meanings across communities, so interpretation requires evidence of its context.",
            "Changes in exhibition and reproduction methods can alter who encounters art and how it is valued.",
            "Support an interpretation with specific visual evidence while acknowledging plausible alternative readings.");
        advanced(books,"arts","arts-studio-inquiry","Studio Inquiry and Original Work","Develop a coherent body of work through experiments and critique.",
            "A studio inquiry begins with a visual question and compares variations that expose the effects of particular choices.",
            "Critique is more actionable when it connects an observed feature to the work's intended effect rather than a taste verdict.",
            "Sequence artworks so contrasts and repetitions create relationships that individual pieces cannot communicate alone.",
            "Document process, provenance and display requirements so an original work can be understood, conserved and presented responsibly.");

        intro(books,"mathematics","math-quantity-patterns","Quantity and Patterns","Use ratios, variables and graphs to describe relationships.",
            "A ratio compares quantities multiplicatively; equal differences do not necessarily mean equal ratios.",
            "A variable represents an unknown or changing quantity, and an equation states a relationship it must satisfy.",
            "A graph's slope describes change in the vertical quantity per unit change in the horizontal quantity.",
            "Percent change divides the change by the original value, so reversing a percentage change requires a different percentage.");
        intro(books,"mathematics","math-shape-measurement","Shape and Measurement","Connect geometry, units and estimates of physical space.",
            "Area measures surface coverage and volume measures occupied space, so their units have different powers of length.",
            "Similar shapes preserve angle measures and side ratios; doubling length multiplies area by four and volume by eight.",
            "The Pythagorean relation links the squared side lengths of a right triangle and can determine an unknown distance.",
            "An order-of-magnitude estimate can detect an implausible result before a detailed calculation is trusted.");
        intermediate(books,"mathematics","math-linear-algebra","Vectors and Linear Systems","Represent simultaneous constraints and transformations.",
            "A vector records magnitude and direction or a list of coordinates, depending on the model being used.",
            "A matrix represents a linear transformation, and matrix multiplication composes compatible transformations in a specified order.",
            "A system of linear equations can have one, none or many solutions depending on independence and consistency of constraints.",
            "Least squares finds a fit that minimizes summed squared residuals, but outliers can strongly influence that fit.");
        intermediate(books,"mathematics","math-probability-statistics","Probability and Statistical Thinking","Calculate conditional uncertainty and interpret samples.",
            "Conditional probability restricts the relevant cases to those where the conditioning event has occurred.",
            "Bayes' rule updates prior probabilities with how likely an observed result is under each competing hypothesis.",
            "Expected value weights possible outcomes by probability, while variance describes dispersion around that average.",
            "Random sampling supports population estimates, but nonresponse and measurement bias can undermine those estimates.");
        advanced(books,"mathematics","math-optimization-models","Optimization and Changing Systems","Use calculus and constraints to search for defensible solutions.",
            "A derivative measures local rate of change, while an integral accumulates contributions across an interval.",
            "An optimization model needs an objective and feasible constraints; the best unconstrained point may be impossible to use.",
            "A convex objective over a convex feasible region makes a local minimum globally optimal, unlike general nonconvex problems.",
            "Differential equations relate a state to its rate of change; initial conditions and stability determine its predicted evolution.");

        intro(books,"physics","physics-motion-forces","Motion and Forces","Explain movement through forces, mass and momentum.",
            "Velocity includes direction, and acceleration describes a change in velocity even when speed remains constant.",
            "A net force changes momentum; for constant mass, acceleration equals net force divided by mass.",
            "Forces between interacting objects are equal and opposite but act on different objects.",
            "Friction depends on contact conditions and can both oppose sliding and provide the grip needed for motion.");
        intro(books,"physics","physics-waves-light","Waves and Light","Relate oscillation, propagation and the behavior of light.",
            "Wave speed equals frequency times wavelength, so changing one quantity can require another to change.",
            "Superposition adds overlapping wave disturbances and can produce reinforcement or cancellation.",
            "Refraction changes a wave's direction when its speed changes across a boundary.",
            "A lens forms images by redirecting light; image position depends on object distance and focal length.");
        intermediate(books,"physics","physics-heat-energy","Heat, Work and Energy","Analyze thermal processes and the limits of energy conversion.",
            "Heat is energy transferred because of a temperature difference, while temperature characterizes a system's thermal state.",
            "The first law relates changes in internal energy to heat added and work done, with a consistent sign convention.",
            "Thermal conduction grows with temperature difference and contact area and decreases with material thickness.",
            "A heat engine requires a temperature difference and cannot convert all received heat into useful work.");
        intermediate(books,"physics","physics-electric-fields","Electricity and Magnetism","Model circuits, fields and electromagnetic energy.",
            "Electric potential difference measures energy per unit charge; current measures the rate at which charge flows.",
            "Charge conservation constrains currents at junctions, and energy conservation constrains voltage changes around a circuit.",
            "A magnetic field exerts a force on moving charges that depends on their direction of motion.",
            "Changing magnetic flux induces an electric effect, which underlies generators and transformers.");
        advanced(books,"physics","physics-modeling-dynamics","Modeling Physical Dynamics","Use conserved quantities, approximations and stability to solve complex motion.",
            "Conservation of energy and momentum can simplify a collision problem when the system boundary and external influences are stated.",
            "Linearizing forces near equilibrium yields small-oscillation models whose accuracy decreases for large departures.",
            "Dimensional scaling identifies dominant effects and allows a model experiment to represent a larger physical system.",
            "Numerical integration error can accumulate; check step-size convergence and conservation behavior before trusting a simulated trajectory.");

        intro(books,"chemistry","chemistry-matter-bonds","Matter and Bonds","Connect particles, bonding and observable material properties.",
            "An element contains one type of atom, while a compound combines elements in a definite chemical relationship.",
            "Chemical bonds involve electron interactions; bonding and structure affect hardness, conductivity and melting behavior.",
            "A change of state rearranges physical organization without necessarily changing the substance's chemical identity.",
            "A balanced chemical equation conserves the number of atoms of each element across a reaction.");
        intro(books,"chemistry","chemistry-solutions-reactions","Solutions and Reactions","Measure mixtures and identify chemical changes.",
            "Concentration expresses solute quantity relative to solution quantity, so dilution changes concentration without adding solute.",
            "Solubility limits how much solute dissolves under specified conditions and can change with temperature.",
            "Acids and bases exchange protons in one common model; pH varies logarithmically with hydrogen ion activity.",
            "Reaction stoichiometry identifies the limiting reactant that determines the maximum amount of product.");
        intermediate(books,"chemistry","chemistry-equilibrium-kinetics","Rates and Equilibria","Separate how fast reactions proceed from where they settle.",
            "A catalyst provides a faster reaction pathway without changing the equilibrium composition of a closed system.",
            "Dynamic equilibrium balances forward and reverse reaction rates even though reactions continue microscopically.",
            "Changing concentration or temperature can shift equilibrium, but the response depends on the reaction's stoichiometry and energetics.",
            "A rate law must be established from evidence because reaction coefficients alone do not generally determine it.");
        intermediate(books,"chemistry","chemistry-organic-materials","Carbon and Functional Materials","Relate molecular structure to reactions and useful materials.",
            "Functional groups help predict an organic molecule's interactions and common reaction behavior.",
            "Molecular shape and polarity affect solubility and intermolecular attraction, influencing material performance.",
            "Polymer chain length, branching and cross-linking affect stiffness, toughness and response to heat.",
            "Chemical separation exploits differences such as volatility, solubility or affinity for a stationary phase.");
        advanced(books,"chemistry","chemistry-process-design","Chemical Process Design","Translate a reaction into a controlled, efficient production process.",
            "A mass balance tracks material entering, leaving and accumulating to detect losses and size process equipment.",
            "Reaction heat and heat-removal capacity must be compared because faster reaction at higher temperature can create runaway feedback.",
            "Process yield, selectivity and conversion describe different outcomes and should not be treated as interchangeable measures.",
            "Choose a process using product quality, energy demand, waste treatment and hazards as well as reaction efficiency.");

        intro(books,"biology","biology-cells-metabolism","Cells and Life","Connect cell structures with energy use and regulation.",
            "A cell membrane controls exchange with the environment while maintaining conditions needed for cellular processes.",
            "Enzymes speed particular reactions, and their activity depends on molecular fit and conditions such as temperature and pH.",
            "Cellular respiration transfers energy from nutrients into forms that can power cellular work.",
            "Negative feedback counteracts a change and helps living systems maintain variables within a workable range.");
        intro(books,"biology","biology-inheritance-evolution","Inheritance and Evolution","Explain biological variation and changes across generations.",
            "DNA stores information used in making cellular products; gene expression determines when that information is used.",
            "Inheritance passes genetic variants between generations, while mutation can introduce new variants.",
            "Natural selection changes variant frequencies when heritable differences affect reproductive success in a particular environment.",
            "Evolution has no required destination; drift and changing environments can also alter populations.");
        intermediate(books,"biology","biology-organism-function","How Organisms Function","Trace exchange, transport and physiological tradeoffs.",
            "Large organisms need transport systems because diffusion alone becomes inefficient across long distances.",
            "Surface area relative to volume constrains exchange rates and helps explain many biological shapes.",
            "Hormonal signals coordinate distant tissues, while local feedback determines how particular tissues respond.",
            "An organism's energy budget divides intake among maintenance, growth and reproduction, creating tradeoffs under scarcity.");
        intermediate(books,"biology","biology-microbial-ecology","Microbes and Communities","Investigate microbial interactions and nutrient cycling.",
            "Microbial communities transform nutrients through different metabolic pathways whose availability depends on environmental conditions.",
            "Competition, cooperation and predation can all shape a microbial community, so abundance alone does not reveal function.",
            "A microbial culture selects organisms suited to its medium and may poorly represent the original community.",
            "Decomposition rate depends on substrate composition, moisture, temperature and the organisms able to process the material.");
        advanced(books,"biology","biology-systems-experiments","Systems Biology and Experiments","Connect mechanisms, network effects and causal perturbations.",
            "A regulatory network links interacting genes and molecules; feedback can produce stability, oscillation or switching.",
            "Perturb one component and measure downstream changes to test a proposed causal pathway rather than relying on association.",
            "Biological replicates estimate variation among organisms, while technical replicates estimate variation in the measurement process.",
            "A mechanistic model should explain observed responses and predict new perturbations before guiding biological design.");

        intro(books,"engineering","engineering-structures-materials","Structures and Materials","Trace loads through structures and select materials for a task.",
            "A load path carries forces from where they are applied through connections to supports.",
            "Stress is force per area and strain is relative deformation; both help compare materials and component sizes.",
            "A beam's geometry influences resistance to bending, so adding material farther from its neutral axis can improve stiffness.",
            "Material selection must consider corrosion, temperature and repeated loading as well as initial strength.");
        intro(books,"engineering","engineering-design-process","Engineering a Useful Object","Turn a need into requirements, alternatives and a tested prototype.",
            "A requirement states a measurable performance target and the conditions in which it must be met.",
            "Compare several concepts against the same criteria so an attractive first idea does not hide a better alternative.",
            "A prototype can test one uncertain mechanism without reproducing every feature of the final product.",
            "Document dimensions, tolerances and assembly steps so another person can build the intended design consistently.");
        intermediate(books,"engineering","engineering-machines-control","Machines and Feedback","Combine mechanisms, sensors and control to achieve stable motion.",
            "Gearing trades rotational speed for torque, subject to losses and component load limits.",
            "Closed-loop control compares measured output with a target and adjusts input to reduce the difference.",
            "Excessive controller gain or measurement delay can cause oscillation instead of faster correction.",
            "Account for backlash, friction and actuator saturation because ideal mechanism equations omit important real constraints.");
        intermediate(books,"engineering","engineering-fluids-thermal","Fluids and Thermal Systems","Estimate flow, pressure losses and heat transfer in equipment.",
            "Continuity relates flow rate to cross-sectional area and velocity for a fluid under stated density assumptions.",
            "Pressure losses increase with flow resistance, so pumps must supply enough head for the complete flow path.",
            "A heat exchanger's performance depends on temperature difference, transfer area and thermal resistance.",
            "Use dimensionless comparisons to determine whether flow is dominated by inertia, viscosity or buoyancy.");
        advanced(books,"engineering","engineering-safety-verification","Safety and Design Verification","Evaluate failure modes and verify a design against its operating envelope.",
            "A failure-mode analysis examines how each component can fail, its consequences and how the failure can be detected.",
            "A safety factor provides margin for stated uncertainties but does not replace checking unmodeled failure mechanisms.",
            "Fatigue and wear require life-cycle testing because repeated subcritical loads can damage an otherwise strong component.",
            "Verification checks specified requirements; validation checks whether the complete design meets the real user's need.");

        intro(books,"economics","economics-scarcity-prices","Scarcity, Choices and Prices","Reason about tradeoffs and how markets coordinate choices.",
            "Opportunity cost is the value of the best alternative forgone when a scarce resource is used.",
            "Demand relates quantities buyers choose to prices and conditions; a changed condition can shift the entire relationship.",
            "A price change affects quantity supplied and demanded, while shortages and surpluses can put pressure on prices.",
            "Marginal reasoning compares the benefit and cost of one additional unit rather than the average of all units.");
        intro(books,"economics","economics-production-trade","Production and Trade","Connect specialization, productivity and exchange.",
            "Productivity measures output relative to inputs and can increase through skills, tools or better organization.",
            "Comparative advantage depends on relative opportunity costs, so trade can help even when one producer is more efficient at everything.",
            "Capital goods support future production but require resources that could be used for current consumption.",
            "Distribution of gains from trade depends on bargaining, adjustment costs and who owns scarce resources.");
        intermediate(books,"economics","economics-macroeconomic-flows","Economic Flows and Cycles","Interpret aggregate production, employment and monetary conditions.",
            "Real output adjusts nominal output for price changes so growth in production is distinguished from inflation.",
            "Saving, investment and borrowing link current income to future production through financial institutions and expectations.",
            "Interest-rate changes affect borrowing and spending with delays, and their effects depend on debt and confidence.",
            "An aggregate average can conceal different employment, income and price experiences across households or regions.");
        intermediate(books,"economics","economics-incentives-institutions","Incentives and Institutions","Analyze externalities, information and rules governing shared resources.",
            "An externality occurs when a decision affects others without that effect being fully reflected in the decision maker's incentives.",
            "Information asymmetry can prevent beneficial exchange when one party cannot judge quality or likely behavior.",
            "Common-resource rules can use monitoring, access limits and agreed enforcement to reduce overuse.",
            "A policy evaluation should consider behavioral responses because people may change choices when incentives change.");
        advanced(books,"economics","economics-market-design","Designing Markets and Policy","Choose mechanisms under strategic behavior and uncertain evidence.",
            "An auction's rules influence bidding behavior, allocation and revenue; compare incentives rather than only posted prices.",
            "A matching system can consider preferences and constraints without assuming money alone expresses the relevant priorities.",
            "Policy incidence asks who ultimately bears a cost, which can differ from the party legally required to pay it.",
            "Evaluate intervention using a credible comparison, distributional effects and unintended substitutions as well as average benefit.");

        intro(books,"finance","finance-budget-cash","Budgets and Cash","Plan everyday inflows, obligations and reserves.",
            "A budget assigns expected income to expenses, saving and obligations over a specified time period.",
            "Separate recurring costs from irregular costs so annual bills do not appear to be unexpected spending.",
            "A cash-flow calendar tracks when money is available, which can matter more than the monthly total.",
            "A reserve absorbs unexpected expenses or lost income; its useful size depends on essential costs and income stability.");
        intro(books,"finance","finance-saving-borrowing","Saving and Borrowing","Understand interest, repayment and the difference between return and risk.",
            "Compound interest earns or charges interest on prior interest, making timing and repayment frequency important.",
            "A loan's total cost includes fees and payment timing as well as its stated interest rate.",
            "A higher expected return usually comes with uncertainty, so promised outcomes should be distinguished from possible outcomes.",
            "Diversification spreads exposure across different sources of risk but cannot remove risks shared by all holdings.");
        intermediate(books,"finance","finance-financial-statements","Reading Financial Statements","Link balance sheets, income and cash movement.",
            "A balance sheet records assets, liabilities and equity at a point in time, with assets equal to liabilities plus equity.",
            "An income statement measures revenue and expenses over a period, including items that do not immediately move cash.",
            "Working capital ties up funds in inventories and receivables while payables can temporarily finance operations.",
            "Compare cash from operations with reported profit to investigate whether earnings are being converted into usable funds.");
        intermediate(books,"finance","finance-investment-appraisal","Appraising Projects","Compare investment alternatives using cash flows and uncertainty.",
            "Discounted cash flow converts future receipts and payments to present value using a rate appropriate to time and risk.",
            "A positive net present value depends on forecast assumptions, so demand and cost scenarios should be tested.",
            "A sunk cost has already been incurred and should not determine whether future benefits justify future costs.",
            "Payback time ignores cash flows after recovery and time value, so it cannot alone establish the best investment.");
        advanced(books,"finance","finance-risk-allocation","Risk and Capital Allocation","Balance liquidity, correlated losses and staged financial commitments.",
            "Portfolio risk depends on covariance between returns, so individually volatile assets can contribute differently to total risk.",
            "Stress testing examines plausible severe scenarios that an average forecast or recent history may fail to capture.",
            "Liquidity risk arises when obligations come due before assets can be converted to cash without major loss.",
            "A staged investment preserves flexibility when later evidence can change whether committing additional capital is worthwhile.");

        intro(books,"design","design-needs-sketches","Needs, Sketches and Prototypes","Discover user needs and test early alternatives cheaply.",
            "Observe a user's task in context to distinguish the requested feature from the problem it is meant to solve.",
            "A journey map records steps, obstacles and decisions so recurring problems can be located in a whole experience.",
            "Sketch several alternatives before refining one to expose assumptions about form and interaction.",
            "A low-fidelity prototype tests an idea quickly when visual polish is not needed to answer the design question.");
        intro(books,"design","design-visual-interfaces","Visual Interfaces","Use hierarchy, feedback and accessibility to support understandable actions.",
            "Visual hierarchy uses size, spacing and contrast to show which information and actions deserve attention first.",
            "An interface should show the result and status of an action so users do not have to guess whether it worked.",
            "Labels and controls need readable contrast and usable target sizes across different devices and abilities.",
            "Group related elements and use consistent terms so users can predict where to find a function.");
        intermediate(books,"design","design-services-systems","Designing Services","Coordinate customer experiences with the operations behind them.",
            "A service blueprint links user-facing steps with staff actions and dependencies that make those steps possible.",
            "Waiting time should be measured alongside its cause because unclear expectations can worsen the experience of a short delay.",
            "Prototype service interactions with realistic staff constraints to uncover promises the organization cannot reliably keep.",
            "Design recovery paths for mistakes so users can regain control without restarting an entire process.");
        intermediate(books,"design","design-products-materials","Product Form and Use","Integrate ergonomics, materials and manufacture into physical design.",
            "Ergonomic dimensions should accommodate a relevant range of users rather than a single average body size.",
            "A form communicates possible actions through grips, openings and visible relationships between parts.",
            "Manufacturing methods impose constraints on thickness, draft, joints and tolerances that early designs must account for.",
            "Evaluate a material against touch, wear, cleaning and end-of-life handling as well as appearance.");
        advanced(books,"design","design-coherent-systems","Design Systems That Evolve","Maintain coherent products through reusable rules and evidence-based revision.",
            "A design system combines reusable components with interaction rules so consistent appearance also supports consistent behavior.",
            "Define tokens for shared values and semantic purposes to change a system coherently without editing every instance.",
            "Usability evidence should connect observed failures to design choices and be checked across distinct user groups.",
            "Govern revisions with compatibility and migration plans so an improvement does not fragment the existing product experience.");

        intro(books,"music","music-rhythm-melody","Rhythm and Melody","Build musical phrases from pulse, meter and pitch.",
            "A pulse provides regular timing, while rhythm describes the durations and placements of sounds against it.",
            "Meter groups beats into repeating patterns of emphasis and influences how a phrase feels.",
            "An interval measures distance between pitches and helps describe a melody's movement.",
            "A phrase can establish an idea through repetition and create direction through a contrasting or unresolved ending.");
        intro(books,"music","music-harmony-listening","Listening to Harmony","Hear relationships among chords, texture and tonal direction.",
            "A chord combines pitches, while harmonic progression describes how chords change through time.",
            "Consonance and dissonance depend partly on context and can create tension, release or ambiguity.",
            "Texture describes how simultaneous musical parts relate, such as a melody with accompaniment or several independent lines.",
            "A bass line can support harmonic roots or create movement that changes the perceived direction of a passage.");
        intermediate(books,"music","music-composition-arranging","Composing and Arranging","Develop motifs and assign parts that work together.",
            "A motif can be transformed by changing rhythm, interval, register or order while retaining a recognizable relationship.",
            "An arrangement balances register and density so important lines remain audible rather than competing in the same range.",
            "Voice leading considers the movement of individual parts between chords instead of treating every chord as an isolated block.",
            "Instrument range, breath and articulation constrain playable phrases and should shape the written part.");
        intermediate(books,"music","music-recording-production","Recording and Production","Capture and mix sound with attention to acoustics and dynamics.",
            "Microphone placement changes the balance of direct sound, room reflections and proximity effects.",
            "Maintain recording headroom to avoid clipping because excessive peaks can create irreversible distortion.",
            "Equalization changes frequency balance, while compression changes dynamic range according to threshold and timing settings.",
            "Check a mix on different playback systems because a balanced studio result may conceal masking or weak bass elsewhere.");
        advanced(books,"music","music-counterpoint-orchestration","Counterpoint and Orchestration","Design large musical structures from independent lines and changing color.",
            "Counterpoint coordinates independent melodic lines through rhythmic relation, contour and treatment of harmonic tension.",
            "Orchestral doubling can strengthen a line or change its color, but balance depends on register and instrument projection.",
            "Large-scale musical form needs contrasts and returns that make local developments meaningful across a longer span.",
            "Revise a score after rehearsal by comparing intended balance and phrasing with what performers can actually realize.");

        intro(books,"literature","literature-close-reading","Close Reading","Interpret prose through language, viewpoint and textual evidence.",
            "A narrator's account is shaped by viewpoint and may differ from what the text allows a reader to infer.",
            "A repeated image or phrase can connect scenes, but its meaning should be tested against its changing context.",
            "Sentence length, word choice and rhythm affect pace and tone as well as conveying literal information.",
            "An interpretation is stronger when it explains specific passages and addresses details that appear to contradict it.");
        intro(books,"literature","literature-poetry-form","Poetry and Form","Explore how sound, line and compression create meaning.",
            "A line break can emphasize a word, delay completion of a thought or create tension with sentence structure.",
            "Meter organizes patterns of stress, while variations can draw attention to a particular moment.",
            "A metaphor relates different things to reveal a connection without requiring every feature to match.",
            "A poem's speaker is a constructed voice and should not automatically be identified with the author.");
        intermediate(books,"literature","literature-narrative-structures","Narrative Structures","Analyze plots, time and character through alternative arrangements.",
            "Story chronology and the order of telling can differ, changing suspense and the reader's understanding of causation.",
            "Character development can emerge from choices under pressure rather than explicit descriptions of personality.",
            "A subplot can echo, challenge or complicate the central conflict instead of merely filling narrative space.",
            "An ending can resolve action while leaving an ethical or emotional question open, depending on the work's aims.");
        intermediate(books,"literature","literature-translation-genres","Texts Across Languages and Genres","Study how conventions and translation choices change a work's reception.",
            "A genre creates expectations that a work may satisfy, rearrange or deliberately frustrate.",
            "Translation balances literal reference, register, rhythm and cultural implication; preserving one may change another.",
            "Compare versions of a passage to identify interpretive choices that disappear when only one translation is read.",
            "Publication context and audience can shape a text's form and which meanings later readers emphasize.");
        advanced(books,"literature","literature-original-manuscripts","Developing Original Manuscripts","Turn a thematic question into a coherent work through revision.",
            "Choose a viewpoint and structure that make the central conflict visible through events rather than explanation alone.",
            "A revision pass should address one level at a time, from overall structure to scenes and then sentence choices.",
            "An image or scene earns its place when it changes understanding, tension or emotional consequence within the whole work.",
            "Use reader responses to diagnose where an intended effect fails while retaining responsibility for the work's artistic choices.");

        intro(books,"philosophy","philosophy-arguments","Arguments and Questions","Distinguish premises, conclusions and the standards of a good argument.",
            "An argument gives premises intended to support a conclusion; a bare assertion supplies no such support.",
            "Validity concerns whether a conclusion follows if premises are true, while soundness also requires true premises.",
            "A counterexample tests a universal claim by finding one case that satisfies its conditions but violates its conclusion.",
            "Clarifying a disputed term can reveal that two speakers defend different claims under the same wording.");
        intro(books,"philosophy","philosophy-ethical-choices","Ethical Choices","Compare consequences, duties and virtues in everyday decisions.",
            "Consequentialist reasoning evaluates outcomes, while duty-based reasoning asks what obligations constrain an action.",
            "Virtue ethics examines traits and practical judgment that shape choices across a person's life.",
            "A conflict of values may require an explicit tradeoff rather than assuming one attractive principle settles everything.",
            "Apply a proposed ethical rule to comparable cases to check whether personal advantage is changing the judgment.");
        intermediate(books,"philosophy","philosophy-knowledge-science","Knowledge and Scientific Explanation","Examine justification, uncertainty and the scope of explanatory claims.",
            "A belief can be true by luck, so accounts of knowledge also ask whether the belief has adequate justification.",
            "Inductive evidence supports expectations without guaranteeing that future unobserved cases will follow the same pattern.",
            "An explanation can identify a cause, a mechanism or a unifying pattern; those aims need different kinds of evidence.",
            "A falsifiable claim exposes itself to possible contrary observations, though interpreting a failed prediction also requires checking assumptions.");
        intermediate(books,"philosophy","philosophy-political-life","Justice and Political Life","Reason about authority, rights and fair institutions.",
            "Political legitimacy concerns whether authority is justified, which differs from whether rulers can enforce decisions.",
            "Equal treatment and equitable treatment can diverge when people begin with different constraints or needs.",
            "A right creates limits or claims on others, so conflicting rights require specifying their scope and justification.",
            "A social-contract argument asks which shared rules people could reasonably accept under stated conditions of choice.");
        advanced(books,"philosophy","philosophy-reasoning-dilemmas","Reasoning Through Dilemmas","Build rigorous positions while confronting uncertainty and conflicting obligations.",
            "Translate an argument into explicit logical form to expose hidden premises and shifts in quantifier scope.",
            "A thought experiment isolates a principle, but its conclusion may weaken if the imagined case removes crucial real constraints.",
            "Reflective equilibrium revises both principles and considered judgments until they cohere without ignoring difficult counterexamples.",
            "A defensible decision under moral uncertainty should explain its reasons, remaining objections and conditions that would change the choice.");

        intro(books,"psychology","psychology-mind-attention","Mind and Attention","Understand perception, memory and limits on attention.",
            "Attention selects some information for processing, so a person can miss a visible event while concentrating on another task.",
            "Memory is reconstructive and can be influenced by later information rather than preserving every experience unchanged.",
            "Working memory has limited capacity, making chunking and external notes useful for complex tasks.",
            "Perception combines incoming signals with expectations, which can improve recognition but also produce systematic errors.");
        intro(books,"psychology","psychology-learning-behavior","Learning and Behavior","Connect experience, practice and reinforcement to behavioral change.",
            "Reinforcement increases the likelihood of a behavior, while its timing and predictability affect how learning proceeds.",
            "Spaced practice distributes learning across time and can support retention better than one concentrated session.",
            "Retrieval practice strengthens access to knowledge and reveals gaps that rereading alone can hide.",
            "A habit links a recurring cue to an action, so changing the environment can alter behavior without relying only on intention.");
        intermediate(books,"psychology","psychology-social-development","Social Minds and Development","Explore how groups, relationships and development affect judgment.",
            "People can conform to a group to gain acceptance or because they believe the group has better information.",
            "Attributing another person's behavior only to character can overlook the pressures of the situation.",
            "Development reflects interaction between biological change and experience, so age alone does not explain every difference.",
            "A group identity can support cooperation while also changing how members interpret evidence about outsiders.");
        intermediate(books,"psychology","psychology-research-methods","Studying Behavior","Design ethical measurements and interpret behavioral evidence.",
            "Operational definitions specify how a psychological construct is measured so another researcher can understand the test.",
            "Reliable measurement is consistent, while valid measurement captures the intended construct; one does not guarantee the other.",
            "Participant expectations and researcher cues can alter behavior, making blinding and standardized procedures useful.",
            "Obtain informed participation and protect privacy while considering whether the study's burden is justified by its question.");
        advanced(books,"psychology","psychology-decision-environments","Designing Better Decisions","Apply behavioral evidence to tasks while checking transfer and unintended effects.",
            "A default option changes decisions through effort and perceived endorsement, so its effect depends on context and available alternatives.",
            "Separate calibration of confidence from accuracy to discover whether people know when their judgments are unreliable.",
            "Test a behavioral intervention in the intended setting because effects from a controlled task may not transfer to everyday decisions.",
            "Measure sustained outcomes and autonomy alongside immediate compliance to avoid treating a short-lived response as lasting improvement.");

        intro(books,"environment","environment-resources-cycles","Resources and Natural Cycles","Connect resource use with water, carbon and material flows.",
            "The water cycle moves water through evaporation, precipitation, infiltration and runoff, linking distant places.",
            "Carbon moves among air, organisms, soils and oceans at different rates, so a rapid release can exceed slow uptake.",
            "Renewable resources can still be depleted when use exceeds their rate of regeneration.",
            "Waste prevention reduces material demand before recycling attempts to recover value from discarded material.");
        intro(books,"environment","environment-habitats-biodiversity","Habitats and Biodiversity","Identify relationships between habitat quality, species and resilience.",
            "A habitat supplies conditions and resources a species needs, so the same place can suit one species and exclude another.",
            "Biodiversity includes variation within species, among species and among ecosystems.",
            "Habitat fragmentation reduces connected space and can prevent organisms from reaching mates or seasonal resources.",
            "An invasive species can change competition and food webs when introduced beyond its previous range.");
        intermediate(books,"environment","environment-climate-energy","Climate and Energy Choices","Compare emissions, energy demand and the consequences of changing climate.",
            "Greenhouse gases affect outgoing heat, and persistent emissions can accumulate even when annual emissions stop increasing.",
            "Compare energy options using lifecycle emissions and useful delivered energy rather than only the point of operation.",
            "Adaptation reduces vulnerability to climate impacts, while mitigation reduces the causes of further warming.",
            "Local climate risk combines hazard, exposure and vulnerability, so the same weather event can have different consequences.");
        intermediate(books,"environment","environment-restoration-monitoring","Restoration and Monitoring","Plan ecosystem repair with baselines and measurable outcomes.",
            "A restoration baseline should describe site history, soil, water and surviving communities before selecting interventions.",
            "Native species selection should reflect local conditions and ecological roles rather than assuming any planting restores a habitat.",
            "Monitor reference and treated sites across time to distinguish intervention effects from weather or wider changes.",
            "Early indicators such as seedling survival need later checks of recruitment and ecosystem function to establish durable recovery.");
        advanced(books,"environment","environment-circular-systems","Circular Resource Systems","Design resource networks while testing material, ecological and social tradeoffs.",
            "A material-flow analysis tracks inputs, stocks and outputs to locate avoidable waste and realistic recovery opportunities.",
            "A reused byproduct must meet quality and contamination constraints before it can safely replace a new material.",
            "Efficiency gains can increase total use when lower costs encourage more consumption, so evaluate system-wide demand.",
            "Compare circular designs across energy use, transport, ecosystem effects and access instead of optimizing a single recycling rate.");

        intro(books,"geography","geography-maps-place","Maps and Place","Interpret spatial information, scale and geographic relationships.",
            "A map's scale relates distance on the map to distance on the ground and limits how much detail it can represent.",
            "Every flat projection distorts some combination of area, distance, direction or shape from the curved Earth.",
            "Coordinates locate a point within a reference system; coordinates from different systems require transformation before comparison.",
            "A map legend explains symbols and classes, while its date determines whether the represented conditions are still relevant.");
        intro(books,"geography","geography-land-water","Land, Water and Settlement","Connect landforms, drainage and patterns of human settlement.",
            "A watershed includes land draining toward a shared outlet, so upstream changes can affect downstream water conditions.",
            "Erosion transports material while deposition accumulates it, changing river channels, slopes and coastlines over time.",
            "Settlement patterns reflect access to water, transport and livelihoods as well as historical choices.",
            "Elevation, slope and land cover influence travel difficulty and runoff, so straight-line distance alone can mislead planning.");
        intermediate(books,"geography","geography-spatial-analysis","Spatial Analysis","Combine geographic layers while checking resolution and uncertainty.",
            "A spatial overlay compares layers at shared locations, but their coordinate systems and resolution must be compatible.",
            "A buffer identifies proximity within a distance, while a network route estimates access along actual connections.",
            "Aggregating data into different boundaries can change apparent patterns even when the underlying observations stay the same.",
            "Spatially nearby observations are often correlated, so treating them as independent can overstate the evidence.");
        intermediate(books,"geography","geography-cities-regions","Cities and Regions","Analyze mobility, land use and uneven access to services.",
            "Accessibility measures which opportunities can be reached within a cost or travel time, not merely the presence of a road.",
            "Land use and transport influence each other because new access changes location choices and new development changes travel demand.",
            "Migration reflects networks, opportunities and constraints, so a single wage comparison rarely explains all movement.",
            "Compare service access across groups and times of day to uncover inequalities hidden by a citywide average.");
        advanced(books,"geography","geography-planning-hazards","Spatial Planning Under Risk","Choose sites and regional strategies using uncertain spatial evidence.",
            "A hazard map describes possible exposure under stated scenarios and should not be treated as a guarantee of safety outside a boundary.",
            "Site selection can combine weighted criteria, but sensitivity checks reveal whether a preferred site depends on arbitrary weights.",
            "Regional planning should test spillovers because protecting one place can shift floodwater, traffic or pollution into another.",
            "Compare planning scenarios with population change, infrastructure life and uncertain hazards to avoid locking in fragile assumptions.");

        Set<String> ids=new HashSet<>();
        Set<String> lessons=new HashSet<>();
        Map<String,Set<String>> depths=new HashMap<>();
        for(Book book:books) {
            if(!ids.add(book.id()))throw new IllegalStateException("Duplicate book ID: "+book.id());
            depths.computeIfAbsent(book.subject(),key->new HashSet<>()).add(book.depth());
            for(String lesson:book.lessons())if(!lessons.add(lesson))
                throw new IllegalStateException("Repeated catalog lesson: "+lesson);
        }
        if(books.size()<100||depths.size()<20||depths.values().stream().anyMatch(d->d.size()!=3))
            throw new IllegalStateException("Catalog must provide broad coverage at every study depth");
        return List.copyOf(books);
    }
}
