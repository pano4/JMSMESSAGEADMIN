/*
 * Automation script: JMSMSGADMIN   (language: groovy, no launch point)
 *
 * Call it in a browser while logged in to Maximo:
 *   https://<host>/maximo/api/script/JMSMSGADMIN
 *   (or /maximo/oslc/script/JMSMSGADMIN, on older 7.6.x)
 *
 * Pages:
 *   no parameters          -> list of all queues from MaxQueueCache
 *   ?queuename=<jndi name> -> queue info + query form
 *   POST action=query      -> count or list messages (selector / maxcount)
 *   POST action=deleterow | deleteselected | deleteall -> delete by JMSMessageID, then re-query
 *
 * It talks to the queue with plain JMS (InitialContext + QueueBrowser / QueueReceiver),
 * so it does not need JMSQueueBrowser or the private MicService.showMessages().
 * javax.jms / jakarta.jms classes are used dynamically, so it works with either.
 */

import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.naming.InitialContext

import psdi.iface.jms.MaxQueueCache
import psdi.server.MXServer

// ---------------------------------------------------------------- settings
// Access is controlled by the script's own security in Maximo.
String SCRIPT_TITLE = "JMS Message Admin"

// ---------------------------------------------------------------- request parameters

Map<String, List<String>> parseQuery(String qs, Map<String, List<String>> into) {
	if (qs == null) return into
	qs = qs.trim()
	if (qs.isEmpty() || qs.startsWith("{") || qs.startsWith("[") || !qs.contains("=")) return into
	for (String pair : qs.split("&")) {
		if (pair == null || pair.isEmpty()) continue
		int idx = pair.indexOf('=')
		String k = URLDecoder.decode(idx >= 0 ? pair.substring(0, idx) : pair, "UTF-8")
		String v = idx >= 0 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : ""
		List<String> l = into.get(k)
		if (l == null) { l = new ArrayList<String>(); into.put(k, l) }
		l.add(v)
	}
	return into
}

/** Adds the keys of src that p does not have yet. */
void mergeParams(Map<String, List<String>> p, Map<String, List<String>> src) {
	src.each { k, v -> if (!p.containsKey(k) && v != null && !v.isEmpty()) p.put(k, v) }
}

/**
 * Reads the request parameters from every place Maximo may put them.
 * Important: once the servlet container (or a Maximo filter) has called getParameter(), the
 * form body of a POST is already consumed, so "requestBody" is empty. The servlet's
 * getParameterMap() still has the form fields, so it is read first.
 */
Map<String, List<String>> readParams(List<String> debug) {
	Map<String, List<String>> p = new LinkedHashMap<String, List<String>>()
	def req = binding.hasVariable("request") ? binding.getVariable("request") : null
	def http = null
	try { http = req?.getHttpServletRequest() } catch (Throwable t) { debug.add("getHttpServletRequest: " + t) }

	// 1) servlet parameters = query string + form body
	if (http != null) {
		try {
			debug.add("method=" + http.getMethod() + ", contentType=" + http.getContentType() + ", queryString=" + http.getQueryString())
			Map<String, List<String>> s = new LinkedHashMap<String, List<String>>()
			http.getParameterMap().each { k, v ->
				List<String> l = new ArrayList<String>()
				if (v instanceof String[]) { for (String x : (String[]) v) l.add(x) } else if (v != null) l.add(v.toString())
				s.put(k.toString(), l)
			}
			debug.add("servlet parameters: " + s)
			mergeParams(p, s)
		} catch (Throwable t) { debug.add("getParameterMap: " + t) }
	}

	// 2) raw POST body (application/x-www-form-urlencoded), if Maximo passes it
	if (binding.hasVariable("requestBody")) {
		def rb = binding.getVariable("requestBody")
		if (rb instanceof byte[]) rb = new String((byte[]) rb, "UTF-8")
		debug.add("requestBody: " + rb)
		Map<String, List<String>> b = new LinkedHashMap<String, List<String>>()
		if (rb != null) parseQuery(rb.toString(), b)
		mergeParams(p, b)
	}

	// 3) query string
	Map<String, List<String>> q = new LinkedHashMap<String, List<String>>()
	try { parseQuery(http?.getQueryString(), q) } catch (Throwable t) { }
	if (q.isEmpty() && req != null) {
		for (String k : ["queuename", "confactname", "action", "selector", "maxcount", "countonly", "debug"]) {
			try {
				def v = req.getQueryParam(k)
				if (v != null) q.put(k, [v.toString()])
			} catch (Throwable t) { }
		}
	}
	mergeParams(p, q)
	debug.add("used parameters: " + p)
	return p
}

String p1(Map<String, List<String>> p, String k, String dflt) {
	List<String> l = p.get(k)
	return (l == null || l.isEmpty() || l.get(0) == null) ? dflt : l.get(0)
}

List<String> pAll(Map<String, List<String>> p, String k) {
	List<String> l = p.get(k)
	return l == null ? new ArrayList<String>() : l.findAll { it != null && !it.trim().isEmpty() }.collect { it.trim() }
}

// ---------------------------------------------------------------- helpers

String esc(def o) {
	if (o == null) return ""
	String s = o.toString()
	StringBuilder b = new StringBuilder(s.length() + 16)
	for (int i = 0; i < s.length(); i++) {
		char c = s.charAt(i)
		switch (c) {
			case '&': b.append("&amp;"); break
			case '<': b.append("&lt;"); break
			case '>': b.append("&gt;"); break
			case '"': b.append("&quot;"); break
			case '\'': b.append("&#39;"); break
			default: b.append(c)
		}
	}
	return b.toString()
}

String enc(String s) { return URLEncoder.encode(s == null ? "" : s, "UTF-8") }

/** QueueConfig has no documented getters for everything, so read its toString(): queueName=..~confactName=..~... */
Map<String, String> parseCfg(def cfg) {
	Map<String, String> m = new LinkedHashMap<String, String>()
	if (cfg == null) return m
	String s = cfg.toString()
	int qi = s.indexOf("queueName=")
	if (qi > 0) s = s.substring(qi)
	for (String part : s.split("~")) {
		if (part.startsWith("initialCtxFactory") && !part.startsWith("initialCtxFactory=")) {
			m.put("initialCtxFactory", part.substring("initialCtxFactory".length()))
			continue
		}
		int e = part.indexOf('=')
		if (e > 0) m.put(part.substring(0, e), part.substring(e + 1))
	}
	return m
}

boolean isA(def o, String simpleName) {
	if (o == null) return false
	for (String pkg : ["javax.jms.", "jakarta.jms."]) {
		for (ClassLoader cl : [o.getClass().getClassLoader(), Thread.currentThread().getContextClassLoader()]) {
			try {
				if (Class.forName(pkg + simpleName, false, cl).isInstance(o)) return true
			} catch (Throwable t) { }
		}
	}
	return false
}

String decodeBody(byte[] b) {
	if (b == null) return ""
	try {
		if (b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
			b = new GZIPInputStream(new ByteArrayInputStream(b)).getBytes()
		} else if (b.length > 2 && (b[0] & 0xff) == 0x78 && ((b[1] & 0xff) == 0x9c || (b[1] & 0xff) == 0xda || (b[1] & 0xff) == 0x01)) {
			b = new InflaterInputStream(new ByteArrayInputStream(b)).getBytes()
		}
	} catch (Throwable t) { /* not compressed after all */ }
	return new String(b, "UTF-8")
}

String readBody(def m) {
	if (isA(m, "TextMessage")) return m.getText() ?: ""
	if (isA(m, "BytesMessage")) {
		m.reset()
		long len = m.getBodyLength()
		byte[] bytes = new byte[(int) len]
		m.readBytes(bytes)
		return decodeBody(bytes)
	}
	if (isA(m, "ObjectMessage")) {
		def o = m.getObject()
		if (o instanceof byte[]) return decodeBody((byte[]) o)
		return String.valueOf(o)
	}
	return "[" + m.getClass().getName() + " - body type not supported]"
}

Map msgInfo(def m) {
	SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
	Map<String, Object> props = new LinkedHashMap<String, Object>()
	long ts = m.getJMSTimestamp()
	props.put("JMSTimestamp", ts > 0 ? df.format(new Date(ts)) : "")
	props.put("JMSCorrelationID", m.getJMSCorrelationID())
	props.put("JMSPriority", m.getJMSPriority())
	props.put("JMSRedelivered", m.getJMSRedelivered())
	props.put("JMSDeliveryMode", m.getJMSDeliveryMode() == 2 ? "PERSISTENT" : "NON_PERSISTENT")
	List<String> names = []
	def en = m.getPropertyNames()
	while (en.hasMoreElements()) names.add(en.nextElement().toString())
	names.sort().each { n -> props.put(n, m.getObjectProperty(n)) }
	String body
	try { body = readBody(m) } catch (Throwable t) { body = "[cannot read body: " + t + "]" }
	return [id: m.getJMSMessageID(), props: props, body: body]
}

// ---------------------------------------------------------------- JMS

Map openJms(String queueName, String cf) {
	def cfg = MaxQueueCache.getInstance().getQueueConfig(queueName)
	if (cfg == null) throw new IllegalArgumentException("Queue is not configured in Maximo: " + queueName)
	if (cf == null || cf.trim().isEmpty() || cf == "null") {
		throw new IllegalArgumentException("No connection factory (confactName) for queue " + queueName)
	}
	cf = cf.trim()

	Properties env = null
	try { env = cfg.getEnv() } catch (Throwable t) { }
	InitialContext ctx = (env != null && !env.isEmpty()) ? new InitialContext(env) : new InitialContext()

	Map j = [ctx: ctx]
	try {
		def qcf = ctx.lookup(cf)
		if (qcf == null) throw new IllegalStateException("JNDI lookup returned null for connection factory " + cf)
		def queue = ctx.lookup(queueName)
		if (queue == null) throw new IllegalStateException("JNDI lookup returned null for queue " + queueName)

		String cred = MXServer.getMXServer().getProperty("mxe.int.queueusercred")
		def conn
		if (cred != null && cred.contains(":")) {
			int i = cred.indexOf(':')
			conn = qcf.createQueueConnection(cred.substring(0, i), cred.substring(i + 1))
		} else {
			conn = qcf.createQueueConnection()
		}
		j.conn = conn
		j.session = conn.createQueueSession(false, 1)   // non-transacted, AUTO_ACKNOWLEDGE
		j.queue = queue
		j.cf = cf
		conn.start()
		return j
	} catch (Throwable t) {
		closeJms(j)
		throw t
	}
}

void closeJms(Map j) {
	if (j == null) return
	try { j.session?.close() } catch (Throwable t) { }
	try { j.conn?.close() } catch (Throwable t) { }
	try { j.ctx?.close() } catch (Throwable t) { }
}

/** countOnly: counts every matching message. Otherwise stops after max messages (max < 0 = all). */
Map browse(String queueName, String confactName, String selector, int max, boolean countOnly) {
	Map j = null
	def browser = null
	try {
		j = openJms(queueName, confactName)
		browser = (selector == null || selector.trim().isEmpty()) ?
				j.session.createBrowser(j.queue) : j.session.createBrowser(j.queue, selector.trim())
		def en = browser.getEnumeration()
		int total = 0
		List<Map> msgs = []
		boolean more = false
		while (en.hasMoreElements()) {
			def m = en.nextElement()
			if (countOnly) { total++; continue }
			if (max >= 0 && msgs.size() >= max) { more = true; break }
			msgs.add(msgInfo(m))
			total++
		}
		return [total: total, messages: msgs, more: more]
	} finally {
		try { browser?.close() } catch (Throwable t) { }
		closeJms(j)
	}
}

/** Removes messages by consuming them with a JMSMessageID selector. */
Map deleteMessages(String queueName, String confactName, List<String> ids) {
	int deleted = 0
	List<String> notFound = []
	Map j = null
	try {
		j = openJms(queueName, confactName)
		for (String id : new LinkedHashSet<String>(ids)) {
			def rcv = j.session.createReceiver(j.queue, "JMSMessageID = '" + id.replace("'", "''") + "'")
			try {
				def m = rcv.receive(3000L)
				if (m != null) deleted++ else notFound.add(id)
			} finally {
				try { rcv.close() } catch (Throwable t) { }
			}
		}
	} finally {
		closeJms(j)
	}
	return [deleted: deleted, notFound: notFound]
}

// ---------------------------------------------------------------- HTML

/** Served with ?resource=css */
String cssCode() {
	return '''
 body{font-family:Segoe UI,Helvetica,Arial,sans-serif;font-size:13px;margin:16px;color:#1d2733;background:#f5f7fa}
 h1{font-size:20px;margin:0 0 12px} h2{font-size:16px;margin:20px 0 8px}
 a{color:#0f62fe;text-decoration:none} a:hover{text-decoration:underline}
 /* inline-block + min-width:100%: a card fills the page width, but grows with a wide table
    (the page then scrolls horizontally instead of the table) */
 .card{display:inline-block;min-width:100%;box-sizing:border-box;background:#fff;border:1px solid #dde3ea;border-radius:6px;padding:12px 16px;margin-bottom:14px}
 table{border-collapse:collapse;width:100%;background:#fff}
 th,td{border:1px solid #dde3ea;padding:4px 8px;text-align:left;vertical-align:top}
 th{background:#eef2f7;position:sticky;top:0;white-space:nowrap}
 tr:hover td{background:#f8fbff}
 /* no own scroll area: the table scrolls with the page, the header row stays at the top of the window */
 .scroll{border:1px solid #dde3ea}
 .kv th{width:180px;position:static}
 a.flt{border-bottom:1px dashed #0f62fe} a.flt:hover{text-decoration:none;background:#e5efff}
 .props{width:auto;min-width:50%} .props th{background:#f7f9fc}
 .muted{color:#6a7785} .nowrap{white-space:nowrap}
 .badge{display:inline-block;padding:1px 6px;border-radius:9px;font-size:11px;background:#e5ecf6}
 .in{background:#dcf2e3} .out{background:#fde9d8} .err{background:#fbdada;color:#9b1c1c}
 .msg{padding:8px 12px;border-radius:4px;margin-bottom:12px}
 .ok{background:#dcf2e3} .bad{background:#fbdada}
 label{margin-right:16px} input[type=text]{width:420px;max-width:100%} input[type=number]{width:90px}
 button{cursor:pointer;padding:3px 10px;border:1px solid #8a99a8;border-radius:4px;background:#fff}
 button.primary{background:#0f62fe;color:#fff;border-color:#0f62fe}
 button.danger{color:#b00020;border-color:#b00020}
 pre{white-space:pre-wrap;word-break:break-all;margin:0;max-height:500px;overflow:auto;background:#fbfcfe;padding:8px;border:1px solid #e3e8ee}
 .toolbar{margin:8px 0;display:flex;gap:8px;flex-wrap:wrap;align-items:center}
 table.ft thead{position:sticky;top:0;z-index:1} table.ft th{position:static}
 th.sortable{cursor:pointer;user-select:none}
 th.sortable:after{content:' \\2195';color:#9aa6b2}
 th.sortable[data-dir=asc]:after{content:' \\25B2';color:#1d2733}
 th.sortable[data-dir=desc]:after{content:' \\25BC';color:#1d2733}
 tr.filters th{background:#f7f9fc;font-weight:normal}
 tr.filters input,tr.filters select{width:100%;box-sizing:border-box;font-size:12px;padding:2px 4px}
'''
}

/** Served with ?resource=js */
String jsCode() {
	return '''
 function toggleRow(id,btn,label){var r=document.getElementById(id);r.hidden=!r.hidden;btn.textContent=(r.hidden?'Show ':'Hide ')+label;}
 /* ---- client-side filter + sort for tables with class "ft" (no server requests) ----
    Rows with class "detail" (properties, message body) belong to the data row above them. */
 function dataRows(t){var r=t.tBodies[0].rows,a=[];for(var i=0;i<r.length;i++){if(!r[i].classList.contains('detail'))a.push(r[i]);}return a;}
 function detailsOf(r){var a=[],n=r.nextElementSibling;while(n&&n.classList.contains('detail')){a.push(n);n=n.nextElementSibling;}return a;}
 function applyFilters(tid){
   var t=document.getElementById(tid);if(!t)return;
   var gi=document.getElementById(tid+'-global'),g=gi?gi.value.trim().toLowerCase():'';
   var f=t.querySelectorAll('thead tr.filters input[data-col], thead tr.filters select[data-col]');
   var rows=dataRows(t),shown=0;
   for(var i=0;i<rows.length;i++){
     var r=rows[i],d=detailsOf(r),all=r.textContent;
     for(var x=0;x<d.length;x++){all+=' '+d[x].textContent;}
     var ok=!g||all.toLowerCase().indexOf(g)>=0;
     for(var j=0;ok&&j<f.length;j++){
       var v=f[j].value.trim().toLowerCase();if(!v)continue;
       var cell=r.cells[parseInt(f[j].getAttribute('data-col'),10)];
       var txt=(cell.getAttribute('data-filter')||cell.textContent).trim().toLowerCase();
       ok=f[j].tagName==='SELECT'?txt===v:txt.indexOf(v)>=0;
     }
     r.style.display=ok?'':'none';for(var y=0;y<d.length;y++){d[y].style.display=ok?'':'none';}
     if(!ok){var cb=r.querySelector('input[name=msgid]');if(cb)cb.checked=false;}
     if(ok)shown++;
   }
   var c=document.getElementById(tid+'-count');
   if(c)c.textContent=shown+' of '+rows.length+' '+(c.getAttribute('data-noun')||'rows');
 }
 function clearFilters(tid){
   var gi=document.getElementById(tid+'-global');if(gi)gi.value='';
   var f=document.querySelectorAll('#'+tid+' thead tr.filters input[data-col], #'+tid+' thead tr.filters select[data-col]');
   for(var i=0;i<f.length;i++){f[i].value='';}
   applyFilters(tid);
 }
 function sortTable(th){
   var t=th.closest('table'),c=th.cellIndex,b=t.tBodies[0];
   var asc=th.getAttribute('data-dir')!=='asc';
   var hs=t.querySelectorAll('thead th.sortable');
   for(var i=0;i<hs.length;i++){hs[i].removeAttribute('data-dir');}
   th.setAttribute('data-dir',asc?'asc':'desc');
   var rows=dataRows(t),pairs=[];
   for(var p=0;p<rows.length;p++){pairs.push([rows[p],detailsOf(rows[p])]);}
   function key(r){var x=r.cells[c];return x?(x.getAttribute('data-sort')||x.textContent).trim():'';}
   pairs.sort(function(a,z){return key(a[0]).localeCompare(key(z[0]),undefined,{numeric:true,sensitivity:'base'})*(asc?1:-1);});
   for(var k=0;k<pairs.length;k++){b.appendChild(pairs[k][0]);for(var q=0;q<pairs[k][1].length;q++){b.appendChild(pairs[k][1][q]);}}
 }
 /* ---- message table actions ---- */
 /* click on an INTERFACE / MSG_TRK_EXTSYS value: add "<prop> = '<value>'" to the Selector (AND) and run the query */
 function filterLike(a){
   var cond=a.getAttribute('data-cond'),sel=document.getElementById('q-selector'),cur=sel.value.trim();
   if(cur.indexOf(cond)<0){sel.value=cur?'('+cur+') AND '+cond:cond;}
   var co=document.getElementById('q-countonly');if(co)co.checked=false;
   document.getElementById('qform').submit();
   return false;
 }
 function setAll(cb){
   var rows=dataRows(cb.closest('table'));
   for(var i=0;i<rows.length;i++){var c=rows[i].querySelector('input[name=msgid]');if(c)c.checked=cb.checked&&rows[i].style.display!=='none';}
 }
 function doAct(a,txt){
   var t=document.getElementById('mtable');
   if(a==='deleteselected'&&!t.querySelector('input[name=msgid]:checked')){alert('No messages selected.');return false;}
   if(a==='deleteall'){
     var form=t.closest('form'),old=form.querySelectorAll('input[name=allid]');
     for(var i=0;i<old.length;i++){old[i].parentNode.removeChild(old[i]);}
     var rows=dataRows(t),n=0;
     for(var j=0;j<rows.length;j++){
       if(rows[j].style.display==='none')continue;
       var inp=document.createElement('input');inp.type='hidden';inp.name='allid';inp.value=rows[j].getAttribute('data-id');
       form.appendChild(inp);n++;
     }
     if(!n){alert('No messages displayed.');return false;}
     txt='Delete all '+n+' displayed messages from the queue?';
   }
   if(!confirm(txt))return false;
   document.getElementById('act').value=a;return true;
 }
'''
}

/** CSS and JavaScript are loaded from this same script with a second request (?resource=css / ?resource=js). */
String pageStart(String title) {
	return '<!doctype html><html><head><meta charset="utf-8">\n' +
			'<meta name="viewport" content="width=device-width, initial-scale=1">\n' +
			'<title>' + esc(title) + '</title>\n' +
			'<link rel="stylesheet" href="?resource=css">\n' +
			'<script src="?resource=js"></script>\n' +
			'</head><body>\n'
}

String pageEnd() { return "</body></html>" }

/** countAll: count the messages of every queue (no selector) and show them in the "Messages" column. */
void renderQueueList(StringBuilder out, boolean countAll) {
	def all = MaxQueueCache.getInstance().getQueueConfig()
	def values = (all instanceof Map) ? ((Map) all).values() : all
	List<Map<String, String>> rows = []
	values.each { cfg -> Map<String, String> m = parseCfg(cfg); if (m.get("queueName")) rows.add(m) }
	rows.sort { it.get("queueName").toLowerCase() }

	Map<String, Object> counts = new HashMap<String, Object>()   // queue -> Integer or Throwable
	long totalMessages = 0
	if (countAll) {
		for (Map<String, String> r : rows) {
			String q = r.get("queueName")
			try {
				int n = (int) browse(q, r.get("confactName"), null, -1, true).total
				counts.put(q, n)
				totalMessages += n
			} catch (Throwable t) {
				service.log_error("JMSMSGADMIN: cannot count messages in " + q + ": " + t)
				counts.put(q, t)
			}
		}
	}

	out.append('<div class="card"><div class="toolbar"><b id="qtable-count" data-noun="queues">').append(rows.size()).append(' of ').append(rows.size()).append(' queues</b>')
	out.append('<input type="text" id="qtable-global" placeholder="Search all columns..." oninput="applyFilters(\'qtable\')" style="width:260px">')
	out.append('<button type="button" onclick="clearFilters(\'qtable\')">Clear filters</button>')
	out.append('<form method="post" action="" style="display:inline"><input type="hidden" name="action" value="countall">')
	out.append('<button type="submit" class="primary">Count messages in all queues</button></form>')
	if (countAll) out.append('<span class="muted">').append(totalMessages).append(' messages in all queues</span>')
	out.append('</div>')
	out.append('<div class="scroll"><table id="qtable" class="ft"><thead><tr>')
	for (String h : ["Queue", "Messages", "Direction", "Type", "Error queue", "Connection factory", "Email"]) {
		out.append('<th class="sortable" onclick="sortTable(this)">').append(h).append('</th>')
	}
	out.append('</tr><tr class="filters">')
	out.append('<th><input type="text" data-col="0" placeholder="contains..." oninput="applyFilters(\'qtable\')"></th>')
	out.append('<th><select data-col="1" onchange="applyFilters(\'qtable\')"><option value="">all</option><option value="has messages">has messages</option><option value="empty">empty</option><option value="error">error</option></select></th>')
	out.append('<th><select data-col="2" onchange="applyFilters(\'qtable\')"><option value="">all</option><option>inbound</option><option>outbound</option></select></th>')
	out.append('<th><select data-col="3" onchange="applyFilters(\'qtable\')"><option value="">all</option><option>sequential</option><option>continuous</option></select></th>')
	out.append('<th><select data-col="4" onchange="applyFilters(\'qtable\')"><option value="">all</option><option>yes</option><option>no</option></select></th>')
	out.append('<th><input type="text" data-col="5" placeholder="contains..." oninput="applyFilters(\'qtable\')"></th>')
	out.append('<th><input type="text" data-col="6" placeholder="contains..." oninput="applyFilters(\'qtable\')"></th>')
	out.append('</tr></thead><tbody>')
	for (Map<String, String> r : rows) {
		String q = r.get("queueName")
		boolean inbound = "true".equals(r.get("inbound"))
		boolean seq = "true".equals(r.get("sequential"))
		boolean err = q.toLowerCase().endsWith("err")
		out.append('<tr><td><a href="?queuename=').append(esc(enc(q)))
		out.append('&amp;confactname=').append(esc(enc(r.get("confactName")))).append('">').append(esc(q)).append('</a></td>')
		def cnt = counts.get(q)
		if (cnt instanceof Integer) {
			int n = (Integer) cnt
			out.append('<td data-sort="').append(n).append('" data-filter="').append(n > 0 ? "has messages" : "empty").append('">')
			out.append(n > 0 ? '<b>' + n + '</b>' : '<span class="muted">0</span>').append('</td>')
		} else if (cnt instanceof Throwable) {
			out.append('<td data-sort="-1" data-filter="error"><span class="badge err" title="').append(esc(cnt.toString())).append('">error</span></td>')
		} else {
			out.append('<td data-sort="" data-filter=""></td>')
		}
		out.append('<td><span class="badge ').append(inbound ? "in" : "out").append('">')
		out.append(inbound ? "inbound" : "outbound").append('</span></td>')
		out.append('<td>').append(seq ? "sequential" : "continuous").append('</td>')
		out.append('<td>').append(err ? '<span class="badge err">yes</span>' : "no").append('</td>')
		out.append('<td>').append(esc(r.get("confactName"))).append('</td>')
		String mail = r.get("emailaddr")
		out.append('<td>').append("null".equals(mail) ? "" : esc(mail)).append('</td></tr>')
	}
	out.append('</tbody></table></div></div>')
}

void renderQueueHeader(StringBuilder out, String queueName, String confactName, String selector, int maxCount, boolean countOnly, String totalHtml) {
	def cfg = MaxQueueCache.getInstance().getQueueConfig(queueName)
	out.append('<p><a href="?">&larr; All queues</a></p>')
	if (cfg == null) {
		out.append('<div class="msg bad">Queue <b>').append(esc(queueName)).append('</b> is not configured in Maximo.</div>')
		return
	}
	Map<String, String> c = parseCfg(cfg)
	out.append('<div class="card"><h2 style="margin-top:0">').append(esc(queueName)).append('</h2><table class="kv">')
	out.append('<tr><th>Messages in queue</th><td style="font-size:16px">').append(totalHtml ?: "").append('</td></tr>')
	c.each { k, v -> out.append('<tr><th>').append(esc(k)).append('</th><td>').append(esc(v)).append('</td></tr>') }
	out.append('</table></div>')

	out.append('<div class="card"><form id="qform" method="post" action="">')
	out.append('<input type="hidden" name="queuename" value="').append(esc(queueName)).append('">')
	out.append('<input type="hidden" name="confactname" value="').append(esc(confactName)).append('">')
	out.append('<input type="hidden" name="action" value="query">')
	out.append('<div class="toolbar">')
	out.append('<label><input type="checkbox" id="q-countonly" name="countonly" value="true"').append(countOnly ? " checked" : "").append('> Count only</label>')
	out.append('<label>Selector <input type="text" id="q-selector" name="selector" value="').append(esc(selector))
	out.append('" placeholder="e.g. INTERFACE = \'MXASSETInterface\'"></label>')
	out.append('<label>Max count <input type="number" name="maxcount" min="-1" value="').append(maxCount).append('"></label>')
	out.append('<button type="submit" class="primary">Query</button>')
	out.append('</div><div class="muted">Max count -1 = all messages.</div>')
	out.append('</form></div>')
}

void renderResults(StringBuilder out, String queueName, String confactName, String selector, int maxCount, Map res) {
	List<Map> msgs = (List<Map>) res.messages

	// columns shown in the table; all other properties are only visible with "Show properties"
	List<String> cols = ["MEAMessageID", "JMSTimestamp", "INTERFACE", "MSG_TRK_EXTSYS", "SENDER"]
	// values in these columns are links that set the Selector and run the query
	List<String> FILTER_LINK_COLS = ["INTERFACE", "MSG_TRK_EXTSYS"]
	int colspan = 4 + cols.size()

	out.append('<div class="card"><h2 style="margin-top:0">Messages</h2>')
	out.append('<div class="muted">Showing ').append(msgs.size()).append(' message(s)')
	if (res.more) out.append(' &ndash; more messages match, raise <i>Max count</i> to see them')
	out.append('.</div>')

	if (msgs.isEmpty()) { out.append('</div>'); return }

	out.append('<form method="post" action="">')
	out.append('<input type="hidden" id="act" name="action" value="query">')
	out.append('<input type="hidden" name="queuename" value="').append(esc(queueName)).append('">')
	out.append('<input type="hidden" name="confactname" value="').append(esc(confactName)).append('">')
	out.append('<input type="hidden" name="selector" value="').append(esc(selector)).append('">')
	out.append('<input type="hidden" name="maxcount" value="').append(maxCount).append('">')
	// "allid" inputs for "Delete all displayed" are added by JavaScript from the rows that are currently visible

	out.append('<div class="toolbar">')
	out.append('<button type="submit" class="danger" onclick="return doAct(\'deleteselected\',\'Delete the selected messages from the queue?\')">Delete selected messages</button>')
	out.append('<button type="submit" class="danger" onclick="return doAct(\'deleteall\',\'\')">Delete all displayed messages</button>')
	out.append('</div>')
	out.append('<div class="toolbar">')
	out.append('<b id="mtable-count" data-noun="messages">').append(msgs.size()).append(' of ').append(msgs.size()).append(' messages</b>')
	out.append('<input type="text" id="mtable-global" placeholder="Search columns, properties and message content..." oninput="applyFilters(\'mtable\')" style="width:300px">')
	out.append('<button type="button" onclick="clearFilters(\'mtable\')">Clear filters</button>')
	out.append('</div>')

	out.append('<div class="scroll"><table id="mtable" class="ft"><thead><tr>')
	out.append('<th><input type="checkbox" title="Select all visible" onclick="setAll(this)"></th>')
	out.append('<th class="sortable" onclick="sortTable(this)">#</th><th>Actions</th>')
	out.append('<th class="sortable" onclick="sortTable(this)">JMSMessageID</th>')
	cols.each { c -> out.append('<th class="sortable" onclick="sortTable(this)">').append(esc(c)).append('</th>') }
	out.append('</tr><tr class="filters"><th></th>')
	out.append('<th><input type="text" data-col="1" placeholder="#" oninput="applyFilters(\'mtable\')"></th><th></th>')
	int fc = 3
	for (int k = 0; k <= cols.size(); k++) {
		out.append('<th><input type="text" data-col="').append(fc + k).append('" placeholder="contains..." oninput="applyFilters(\'mtable\')"></th>')
	}
	out.append('</tr></thead><tbody>')

	int i = 0
	for (Map m : msgs) {
		i++
		String rowId = "body" + i
		String propId = "props" + i
		out.append('<tr data-id="').append(esc(m.id)).append('"><td><input type="checkbox" name="msgid" value="').append(esc(m.id)).append('"></td>')
		out.append('<td>').append(i).append('</td>')
		out.append('<td class="nowrap">')
		out.append('<button type="button" onclick="toggleRow(\'').append(rowId).append('\',this,\'message\')">Show message</button> ')
		out.append('<button type="button" onclick="toggleRow(\'').append(propId).append('\',this,\'properties\')">Show properties</button> ')
		out.append('<button type="submit" class="danger" name="rowid" value="').append(esc(m.id))
		out.append('" onclick="return doAct(\'deleterow\',\'Delete this message from the queue?\')">Delete</button></td>')
		out.append('<td class="nowrap">').append(esc(m.id)).append('</td>')
		Map props = (Map) m.props
		cols.each { c ->
			def v = props.get(c)
			if (v != null && v.toString() != "" && c in FILTER_LINK_COLS) {
				// click on the value -> Selector "<c> = '<value>'" and run the query on the server
				String cond = c + " = '" + v.toString().replace("'", "''") + "'"
				out.append('<td data-sort="').append(esc(v)).append('" data-filter="').append(esc(v)).append('">')
				out.append('<a href="#" class="flt" data-cond="').append(esc(cond)).append('" title="Query messages with ')
				out.append(esc(cond)).append('" onclick="return filterLike(this)">').append(esc(v)).append('</a></td>')
			} else {
				out.append('<td>').append(esc(v)).append('</td>')
			}
		}
		out.append('</tr>')
		// all properties (detail row)
		out.append('<tr id="').append(propId).append('" class="detail" hidden><td colspan="').append(colspan).append('">')
		out.append('<table class="kv props">')
		props.each { k, v -> out.append('<tr><th>').append(esc(k)).append('</th><td>').append(esc(v)).append('</td></tr>') }
		out.append('</table></td></tr>')
		// message body (detail row)
		out.append('<tr id="').append(rowId).append('" class="detail" hidden><td colspan="').append(colspan).append('"><pre>')
		out.append(esc(m.body)).append('</pre></td></tr>')
	}
	out.append('</tbody></table></div></form></div>')
}

// ---------------------------------------------------------------- main

List<String> debugInfo = []
Map<String, List<String>> params = readParams(debugInfo)

// static resources requested by the page itself: ?resource=css / ?resource=js
String resource = p1(params, "resource", "").trim().toLowerCase()
if (resource == "css") {
	responseHeaders.put("content-type", "text/css")
	responseBody = cssCode()
	return
}
if (resource == "js") {
	responseHeaders.put("content-type", "application/javascript")
	responseBody = jsCode()
	return
}
boolean debug = "1".equals(p1(params, "debug", "")) || "true".equalsIgnoreCase(p1(params, "debug", ""))
String queueName = p1(params, "queuename", "").trim()
String confactName = p1(params, "confactname", "").trim()
if ((confactName.isEmpty() || confactName == "null") && !queueName.isEmpty()) {
	// not passed (e.g. URL typed by hand) -> take it from the queue configuration
	String cfgCf = parseCfg(MaxQueueCache.getInstance().getQueueConfig(queueName)).get("confactName")
	confactName = cfgCf == null ? "" : cfgCf
}
String action = p1(params, "action", "").trim().toLowerCase()
String selector = p1(params, "selector", "")
boolean countOnly = "true".equalsIgnoreCase(p1(params, "countonly", "false")) || "on".equalsIgnoreCase(p1(params, "countonly", ""))
int maxCount = 1
try { maxCount = Integer.parseInt(p1(params, "maxcount", "1").trim()) } catch (Exception e) { maxCount = 1 }

StringBuilder out = new StringBuilder()
out.append('<h1>').append(esc(SCRIPT_TITLE)).append(' <span class="muted" style="font-size:12px;font-weight:normal">user: ')
out.append(esc(userInfo.getUserName())).append('</span></h1>')

try {
	if (queueName.isEmpty()) {
		renderQueueList(out, action == "countall")
	} else {
		if (action.startsWith("delete")) countOnly = false
		boolean configured = MaxQueueCache.getInstance().getQueueConfig(queueName) != null

		// 1) deletions first, so the total count below is already up to date
		StringBuilder banner = new StringBuilder()
		if (configured && action.startsWith("delete")) {
			List<String> ids
			if (action == "deleterow") ids = pAll(params, "rowid")
			else if (action == "deleteselected") ids = pAll(params, "msgid")
			else if (action == "deleteall") ids = pAll(params, "allid")
			else ids = []

			if (ids.isEmpty()) {
				banner.append('<div class="msg bad">No messages to delete.</div>')
			} else {
				Map d = deleteMessages(queueName, confactName, ids)
				service.log_info("JMSMSGADMIN: user " + userInfo.getUserName() + " deleted " + d.deleted + " message(s) from " + queueName + ": " + ids)
				banner.append('<div class="msg ').append(d.notFound.isEmpty() ? "ok" : "bad").append('">Deleted ')
				banner.append(d.deleted).append(' of ').append(ids.size()).append(' message(s).')
				if (!d.notFound.isEmpty()) {
					banner.append(' Not found (already processed or deleted): ').append(esc(d.notFound.join(", ")))
				}
				banner.append('</div>')
			}
		}

		// 2) total number of messages in the queue (no selector), shown with the queue info
		String totalHtml = null
		if (configured) {
			try {
				totalHtml = '<b>' + browse(queueName, confactName, null, -1, true).total + '</b>'
			} catch (Throwable t) {
				service.log_error("JMSMSGADMIN: cannot count messages in " + queueName + ": " + t)
				totalHtml = '<span class="badge err">cannot count: ' + esc(t.toString()) + '</span>'
			}
		}

		renderQueueHeader(out, queueName, confactName, selector, maxCount, countOnly, totalHtml)
		out.append(banner)

		if (configured && action) {
			Map res = browse(queueName, confactName, selector, maxCount, countOnly)
			debugInfo.add("browse: queue=" + queueName + ", cf=" + confactName + ", selector=[" + selector + "], maxCount=" + maxCount
					+ ", countOnly=" + countOnly + " -> total=" + res.total + ", listed=" + ((List) res.messages).size() + ", more=" + res.more)
			if (countOnly) {
				out.append('<div class="card"><h2 style="margin-top:0">Count</h2><p style="font-size:18px"><b>')
				out.append(res.total).append('</b> message(s) in <b>').append(esc(queueName)).append('</b>')
				if (selector?.trim()) out.append(' matching <code>').append(esc(selector.trim())).append('</code>')
				out.append('</p></div>')
			} else {
				renderResults(out, queueName, confactName, selector, maxCount, res)
			}
		}
	}
} catch (Throwable t) {
	service.log_error("JMSMSGADMIN failed: " + t)
	StringWriter sw = new StringWriter()
	t.printStackTrace(new PrintWriter(sw))
	out.append('<div class="msg bad"><b>Error:</b> ').append(esc(t.toString())).append('</div>')
	out.append('<details><summary>Stack trace</summary><pre>').append(esc(sw.toString())).append('</pre></details>')
}

if (debug) {
	debugInfo.add("action=[" + action + "], queueName=[" + queueName + "], confactName=[" + confactName + "], countOnly=" + countOnly + ", maxCount=" + maxCount)
	out.append('<details open class="card"><summary><b>Debug</b></summary><pre>').append(esc(debugInfo.join("\n"))).append('</pre></details>')
	service.log_info("JMSMSGADMIN debug:\n" + debugInfo.join("\n"))
}

responseHeaders.put("content-type", "text/html")
responseBody = pageStart(SCRIPT_TITLE) + out.toString() + pageEnd()
