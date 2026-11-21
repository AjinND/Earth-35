import type {ActivityProgress,Agent} from "./types";

// Identity comes from the resident ID, so renaming or sorting never changes a coat.
export function avatarStyle(id:string) {
  let seed=2166136261;
  for(const char of id) seed=Math.imul(seed^char.charCodeAt(0),16777619)>>>0;
  const coats=["#e7c7a3","#bbcbb2","#bed4e5","#d5bddc","#e6b9aa","#e5dba7"];
  const scarves=["#637d62","#b86b50","#567e9c","#936d89","#c49648"];
  return {seed,coat:coats[seed%coats.length],accent:scarves[(seed>>>4)%scarves.length],longEars:(seed>>>8)%2===0};
}
export type AvatarMotion="Idle"|"Walk"|"Run"|"Jump"|"Rest"|"Sleep"|"Work"|"Build"|"Wave"|"Search"|"Observe"|"Activity"|"Read"|"Research"|"Assemble"|"Produce"|"Install"|"Listen"|"Talk"|"Eat"|"Drink";
export function avatarMotion(activity:string,progress?:ActivityProgress|null):AvatarMotion {
  if(activity==="DO_ACTIVITY"&&progress) {
    if(progress.phase==="blocked")return "Idle";
    if(progress.phase==="travel")return "Walk";
    // A timer at a location cannot establish a physical task for the avatar.
    return progress.mode==="search"?"Search":progress.mode==="observe"?"Observe":"Idle";
  }
  return ({MOVE:"Walk",BOARD_BUS:"Walk",RUN:"Run",JUMP:"Jump",REST:"Rest",SLEEP:"Sleep",WORK:"Work",BUILD_CONSTRUCTION:"Build",BUILD_PROJECT:"Work",PRODUCE_PRODUCT:"Work",PLACE_OBJECT:"Work",MOVE_OBJECT:"Work",READ_BOOK:"Read",TALK:"Wave",CHAT:"Wave"} as Record<string,AvatarMotion>)[activity]??"Idle";
}

/** Interpret a recorded physical phase; the renderer never chooses the task. */
export function avatarPerformance(activity:string,progress:ActivityProgress|null|undefined,performance:Agent["performance"],moving=false,seated=false,constructionWorking=true) {
  let motion:AvatarMotion="Idle";
  if(moving)motion=activity==="RUN"?"Run":"Walk";
  else if(performance) {
    if(performance.phase==="perform") {
      const gestures:Record<string,AvatarMotion>={jump:"Jump",read:"Read",work:performance.action==="BUILD_PROJECT"?"Assemble":"Work",build:performance.action==="BUILD_PROJECT"?"Assemble":"Build",research:"Research",produce:"Produce",place:"Install",observe:"Observe",listen:"Listen",talk:"Talk",eat:"Eat",drink:"Drink",idle:"Idle"};
      motion=performance.posture==="lying"?"Sleep":gestures[performance.gesture]??"Idle";
      if(motion==="Idle"&&performance.posture==="seated")motion="Rest";
    }
  } else {
    motion=avatarMotion(activity,progress);
    if(["MOVE","RUN","BOARD_BUS"].includes(activity)||motion==="Walk"||activity==="BUILD_CONSTRUCTION"&&!constructionWorking)motion="Idle";
    if(seated&&activity!=="READ_BOOK")motion="Rest";
  }
  return {motion,posture:moving?"standing":performance?.posture??(seated?"seated":motion==="Sleep"?"lying":"standing"),
    heldItem:performance?performance.heldItem??null:motion==="Read"?"book":motion==="Build"?"construction-tool":null,
    anchored:performance?.anchored??false};
}

export function performanceHeading(performance:Agent["performance"],moving:boolean) {
  return !moving&&performance?.heading!=null&&Number.isFinite(performance.heading)?performance.heading:undefined;
}
