# JMSMESSAGEADMIN

Maximo script to browse and delete JMS queue messages.

`JMSMESSAGEADMIN` is a Groovy automation script for IBM Maximo / Maximo Manage. It serves a single-page web app that lists the integration (MIF) JMS queues configured in Maximo. From there you can count, inspect and delete the messages in each queue, for example to clean up `cqinerr` or check what is stuck in a sequential queue.

## Features

- **Queue list:** shows every queue from `MaxQueueCache`, with its direction (inbound/outbound), type (sequential/continuous), whether it is an error queue, its connection factory and its notification email.
  - Each column has a filter, and there is also a search box for all columns.
  - Click a column header to sort by it. Filtering and sorting happen in the browser, without calling the server again.
  - **Count messages in all queues** counts the messages in every queue (without a selector) and shows the numbers in a *Messages* column, which you can sort and filter (has messages / empty / error).
  - Each queue also has its own **Count messages** button, which counts just that queue and updates its cell without reloading the page.
- **Queue page:** shows the total number of messages in the queue (counted straight away, without a selector, with a **Refresh** button), the queue's configuration and a query form:
  - **Count only:** shows how many messages match.
  - **Selector:** a standard JMS message selector, for example `INTERFACE = 'MXASSETInterface'`.
  - **Max count:** how many messages to list (`-1` lists all of them).
- **Message table:** one row per message, with the columns JMSMessageID, MEAMessageID, JMSTimestamp, INTERFACE, MSG_TRK_EXTSYS and SENDER.
  - Clicking one of these values adds it to the Selector (for example `INTERFACE = 'MXASSETInterface'`) and runs the query again.
  - **Show properties** shows all JMS headers and message properties of a message.
  - Each column has a filter, and a search box covers all columns plus the message body.
  - Click a column header to sort by it. As with the queue list, this happens in the browser.
  - **Download** saves the message body as `SENDER_INTERFACE_MEAMessageID.xml`, and **Download displayed messages (zip)** saves all visible messages in one zip file. Both are built in the browser.
  - **Show message** expands the message body. Gzip/zlib-compressed bodies are unpacked first.
  - **Delete** removes that one message.
  - **Delete selected messages** removes the messages you ticked.
  - **Delete all displayed messages** removes the messages currently shown in the table, and nothing else.
- Messages are deleted by `JMSMessageID`, so only the messages you picked are removed.
- Every deletion is written to the Maximo log, with the user name and the message IDs.

## How it works

The page is loaded only once. The first request returns a minimal HTML page, which loads the stylesheet (`resource=css`) and the JavaScript (`resource=js`) from the same script. The JavaScript builds the whole user interface, and every later action (listing queues, counting, querying, deleting) is a JSON data request to the script (`resource=json`). You always stay on the same page; the browser's address only changes after the `#`, so the back button and bookmarks of a queue still work.

The script talks to the queues with plain JMS calls: it looks up the queue and its connection factory through JNDI (`InitialContext`), and reads messages with a `QueueBrowser`. Messages are deleted with Maximo's own MIC service, `MicServiceRemote.deleteQueueData(queueName, "JMSMessageID = '<id>'", 1, null)`, one call per message. It does not use `psdi.iface.jms.JMSQueueBrowser`, and it does not write files to the global directory.

The JMS classes are called dynamically, so the script works with both `javax.jms` and `jakarta.jms`.

## Requirements

- IBM Maximo 7.6.1.x or Maximo Manage (MAS), with automation scripts that can be called over HTTP and that support the `responseHeaders` variable.
- The Maximo JMS integration queues are configured in **External Systems → Add/Modify Queues**.
- The script must run on a Maximo server/JVM that can reach those queues through JNDI.
- If your JMS provider requires a login, set the `mxe.int.queueusercred` system property to `user:password`.

## Installation

1. In Maximo, open **Automation Scripts** and choose **Create → Script**.
2. Create the script:
   - **Script:** `JMSMESSAGEADMIN`
   - **Language:** `groovy`
   - **Source:** the contents of `src/JMSMESSAGEADMIN.groovy`
3. Save it and set the script to **Active**.
4. Restrict who can run it using Maximo's security for automation scripts. The script does no permission checks of its own, and anyone who can run it can delete messages.

## Usage

While logged in to Maximo, open:

```
https://<host>/maximo/api/script/JMSMESSAGEADMIN
```

On older 7.6 systems, use `https://<host>/maximo/oslc/script/JMSMESSAGEADMIN` instead.

To open a queue directly, add its name after the `#`, for example `…/JMSMESSAGEADMIN#queue=jms%2Fmaximo%2Fint%2Fqueues%2Fcqinerr`.

### Requests

| Request | Returns |
|---|---|
| *(no parameters)* | The minimal HTML page. |
| `resource=css` | The stylesheet. |
| `resource=js` | The JavaScript. |
| `resource=json&op=queues` | All queues and the user name. |
| `resource=json&op=queue&queuename=…` | The configuration of one queue. |
| `resource=json&op=count&queuename=…[&selector=…]` | The number of (matching) messages. |
| `resource=json&op=messages&queuename=…&selector=…&maxcount=…` | The messages with headers, properties and body. `maxcount` `-1` returns all. |
| `resource=json&op=delete&queuename=…&id=…&id=…` | Deletes the messages with these JMSMessageIDs. Must be a POST. |

All JSON requests also accept `confactname`; without it, the script takes the connection factory from the queue's configuration. On failure they return `{"error": "…"}`, which the page shows at the top.

## Notes

- **Messages can vanish before you delete them.** On queues that Maximo is actively consuming, such as a `cqin` with its message-driven beans running, a message can be processed between the query and the delete. The page reports those messages as "not found".
- **Count only can be slow.** It reads through every matching message, which takes a while on very large queues.
- **Deletions can't be undone.** Export or copy anything you might need before deleting it.
