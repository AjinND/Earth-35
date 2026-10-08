import {walkElevation,type IslandPoint} from "./island";

export function effortMultiplier(grade:number) {
  const up=Math.max(grade,0),down=Math.max(-grade,0);
  return Math.sqrt(1+grade*grade)*(1+4*up+3*Math.max(down-.08,0))/(1+1.2*Math.min(.08,down));
}
export function energyMultiplier(grade:number) {
  return 1+2.5*Math.max(grade,0)+.5*Math.max(-grade-.08,0);
}
// Match the server's directed, half-unit surface survey. Gentle descents are
// easier; steep descents require braking. Flat city speeds stay unchanged.
export function terrainEffort(from:IslandPoint,to:IslandPoint) {
  const length=Math.hypot(to.x-from.x,to.z-from.z),steps=Math.max(1,Math.ceil(length/.5));
  let previous=walkElevation(from.x,from.z),walkingEffort=0,runningEnergyEffort=0,maxGrade=0;
  if(length===0)return {walkingEffort,runningEnergyEffort,maxGrade};
  for(let index=1;index<=steps;index++) {
    const t=index/steps,height=walkElevation(from.x+(to.x-from.x)*t,from.z+(to.z-from.z)*t),distance=length/steps;
    const grade=(height-previous)/distance,effort=distance*effortMultiplier(grade);
    walkingEffort+=effort;runningEnergyEffort+=effort*energyMultiplier(grade);maxGrade=Math.max(maxGrade,Math.abs(grade));previous=height;
  }
  return {walkingEffort,runningEnergyEffort,maxGrade};
}

export function terrainStep(from:IslandPoint,to:IslandPoint,budget:number) {
  const total=terrainEffort(from,to).walkingEffort;
  if(total<=budget||total===0)return to;
  const ratio=budget/total,estimate={x:from.x+(to.x-from.x)*ratio,z:from.z+(to.z-from.z)*ratio};
  if(Math.abs(terrainEffort(from,estimate).walkingEffort-budget)<1e-8)return estimate;
  let low=0,high=1;
  for(let iteration=0;iteration<12;iteration++) {
    const t=(low+high)/2,point={x:from.x+(to.x-from.x)*t,z:from.z+(to.z-from.z)*t};
    if(terrainEffort(from,point).walkingEffort<=budget)low=t;else high=t;
  }
  return {x:from.x+(to.x-from.x)*low,z:from.z+(to.z-from.z)*low};
}
