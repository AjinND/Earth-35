export type WindSample={
  /** Horizontal travel velocity and magnitude in metres per second. */
  x:number;z:number;speed:number;
  /** Bounded rendering intensity; 12 m/s reaches full strength. */
  strength:number;
  /** Signed, coherent gust signal in [-1,1], shared across weather conditions. */
  gust:number;
  direction:{x:number;z:number};
};

const clamp=(value:number,min:number,max:number)=>Math.max(min,Math.min(max,value));
const finite=(value:number,fallback=0)=>Number.isFinite(value)?value:fallback;
const referenceHeight=10,roughness=.5,maxHeight=120;

/**
 * A shared wind field for world minutes, rendered map positions and height in
 * metres above ground. The returned velocity is also in metres per second.
 * Broad smooth gusts replace independent per-object noise. A logarithmic shear
 * profile models surface drag, with a small residual at the unresolved ground
 * layer. This is an animation field, rather than a fluid or terrain-flow solver.
 */
export function windAt(minute:number,weather:string,x=0,z=0,height=referenceHeight):WindSample {
  const time=finite(minute),px=finite(x),pz=finite(z),aboveGround=clamp(finite(height,referenceHeight),0,maxHeight);
  const heading=.8+.28*Math.sin(time*.012)+.12*Math.sin(time*.031+1.7);
  const angle=heading+.045*Math.sin(px*.002+pz*.0015+time*.017);
  const direction={x:Math.cos(angle),z:Math.sin(angle)};
  // Bounded modes give continuous gusts at nearby positions and across clock
  // intervals. Time never wraps at midnight, so neither does the wind field.
  const gust=.55*Math.sin(time*.63-px*.018-pz*.012)+
    .3*Math.sin(time*.27+px*.007-pz*.009+1.2)+
    .15*Math.sin(time*.11-px*.004+pz*.003+2.4);
  const baseline=weather==="Rain"?7:weather==="Overcast"?4:2;
  const shear=.25+.75*Math.log1p(aboveGround/roughness)/Math.log1p(referenceHeight/roughness);
  const speed=baseline*shear*(1+.3*gust);
  return {x:direction.x*speed,z:direction.z*speed,speed,strength:clamp(speed/12,0,1),gust,direction};
}
