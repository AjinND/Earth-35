import type {ConstructionSiteView} from "./types";

/** Progress comes only from recorded labor, including the on-site portion of active shifts. */
export function constructionProgress(site?:ConstructionSiteView) {
  const required=Math.max(0,site?.requiredWorkMinutes??(site?.requiredHours??0)*60);
  const completed=Math.max(0,site?.completedWorkMinutes??(site?.completedHours??0)*60);
  return {completed,required,fraction:required>0?Math.min(1,completed/required):0,percent:required>0?Math.min(100,Math.floor(completed/required*100)):0};
}

export function constructionStage(fraction:number) {
  return fraction<.12?"Foundations":fraction<.76?"Structural frame":fraction<.95?"Walls and services":"Finishing";
}
