export type DayPhase="Night"|"Dawn"|"Daytime"|"Dusk";

export function dayPhase(minute:number):DayPhase {
  const time=((minute%1440)+1440)%1440;
  if(time<300||time>=1200)return "Night";
  if(time<420)return "Dawn";
  if(time<1080)return "Daytime";
  return "Dusk";
}

const smooth=(low:number,high:number,value:number)=>{
  const t=Math.max(0,Math.min(1,(value-low)/(high-low)));
  return t*t*(3-2*t);
};

export function environmentAt(minute:number,weather:string) {
  const time=((minute%1440)+1440)%1440;
  const angle=(time-360)/1440*Math.PI*2;
  const elevation=Math.sin(angle);
  const daylight=smooth(-.18,.3,elevation);
  const twilight=(1-smooth(.02,.35,Math.abs(elevation)))*smooth(-.23,0,elevation);
  const cloud=weather==="Rain"?1:weather==="Overcast"?.75:.08;
  return {phase:dayPhase(minute),daylight,twilight,cloud,rain:weather==="Rain"?1:0,
    sun:[-Math.cos(angle)*110,elevation*110,40] as [number,number,number]};
}
