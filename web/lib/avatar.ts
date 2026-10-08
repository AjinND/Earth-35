// Identity comes from the resident ID, so renaming or sorting never changes a coat.
export function avatarStyle(id:string) {
  let seed=2166136261;
  for(const char of id) seed=Math.imul(seed^char.charCodeAt(0),16777619)>>>0;
  const coats=["#e7c7a3","#bbcbb2","#bed4e5","#d5bddc","#e6b9aa","#e5dba7"];
  const scarves=["#637d62","#b86b50","#567e9c","#936d89","#c49648"];
  return {seed,coat:coats[seed%coats.length],accent:scarves[(seed>>>4)%scarves.length],longEars:(seed>>>8)%2===0};
}
export type AvatarMotion="Idle"|"Walk"|"Run"|"Jump"|"Rest"|"Sleep"|"Work"|"Build"|"Wave";
export function avatarMotion(activity:string):AvatarMotion {
  return ({MOVE:"Walk",BOARD_BUS:"Walk",RUN:"Run",JUMP:"Jump",REST:"Rest",SLEEP:"Sleep",WORK:"Work",BUILD_CONSTRUCTION:"Build",BUILD_PROJECT:"Work",PRODUCE_PRODUCT:"Work",PLACE_OBJECT:"Work",MOVE_OBJECT:"Work",TALK:"Wave",CHAT:"Wave"} as Record<string,AvatarMotion>)[activity]??"Idle";
}
