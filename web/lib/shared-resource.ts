type Entry<T>={value:T;users:number;timer:ReturnType<typeof setTimeout>|undefined};

/** Scene-independent resources survive brief unmounts, with bounded idle retention. */
export function sharedResourceCache<K,T>(dispose:(value:T)=>void,idleMs=30000,maxIdle=1) {
  const entries=new Map<K,Entry<T>>();
  const remove=(key:K,entry:Entry<T>)=>{
    if(entry.users||entries.get(key)!==entry)return;
    clearTimeout(entry.timer);entries.delete(key);dispose(entry.value);
  };
  const idle=(key:K,entry:Entry<T>)=>{
    clearTimeout(entry.timer);
    entry.timer=setTimeout(()=>remove(key,entry),idleMs);
    // Node-based geometry checks should not wait for a browser cache timer.
    if(typeof entry.timer==="object")entry.timer.unref?.();
    const unused=[...entries].filter(([,item])=>item.users===0);
    for(const [oldKey,oldEntry] of unused.slice(0,Math.max(0,unused.length-maxIdle)))remove(oldKey,oldEntry);
  };
  return {
    get(key:K,create:()=>T) {
      const existing=entries.get(key);if(existing)return existing.value;
      const entry:Entry<T>={value:create(),users:0,timer:undefined};entries.set(key,entry);idle(key,entry);return entry.value;
    },
    retain(key:K) {
      const entry=entries.get(key);if(!entry)throw new Error("Shared resource must be created before retaining it");
      clearTimeout(entry.timer);entry.users++;
      let released=false;
      return ()=>{if(released)return;released=true;if(--entry.users===0)idle(key,entry);};
    },
  };
}
