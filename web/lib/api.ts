export async function api<T>(path:string,method="GET",body?:unknown):Promise<T> {
  const res=await fetch(`/api/v1${path}`,{method,headers:{"Content-Type":"application/json","X-E35-Client":"web"},body:body===undefined?undefined:JSON.stringify(body),cache:"no-store"});
  const data=await res.json();if(!res.ok) throw new Error(data.error||"Request failed");return data;
}
export const time=(minute:number)=>`${String(Math.floor(minute%1440/60)).padStart(2,"0")}:${String(Math.floor(minute%60)).padStart(2,"0")}`;
export const money=(cents:number)=>`${(cents/100).toLocaleString("en-US",{minimumFractionDigits:2,maximumFractionDigits:2})} E`;
