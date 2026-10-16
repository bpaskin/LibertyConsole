# Security

## Authentication and roles

The console uses the server's **management roles**, the same ones Admin Center and the REST
connector use. It doesn't use an application role binding.

| server.xml | Console role | Access |
|---|---|---|
| `<administrator-role>` | Administrator | everything |
| `<reader-role>` | Reader | read-only; anything that changes the server returns HTTP 403 |

Users come from whatever registry the server has configured (`basicRegistry`, `ldapRegistry-3.0`,
federated repositories, ...). Sign-in uses the FORM login page. A user who signs in successfully
but has neither role gets HTTP 403.

The feature ships **no users or passwords**. Define them in the server's user registry.

## Transport

* Everything except the login page requires HTTPS (`CONFIDENTIAL`). HTTP requests redirect to the HTTPS port.
* Cookies are `HttpOnly` and `Secure`. A sign-in lasts as long as the server's LTPA token
  (`<ltpa expiration="..."/>`, 2 hours by default).
* The feature turns on `transportSecurity-1.0`. Give the server a keystore, for example
  `<keyStore id="defaultKeyStore" password="${keystore_password}"/>`, and use a CA-signed certificate in production.

## Protections in the API

* **Cross-site request forgery**: POST, PUT and DELETE requests whose `Origin` header names a different site are refused (403).
* **Files**: the configuration editor reads and writes only inside the server configuration directory.
  It only writes configuration file types, doesn't follow symbolic links out of the directory, and
  checks server.xml (well-formed, `<server>` root, no DTD/external entities) before saving it.
  Every write keeps a timestamped backup next to the file.
* **Logs**: only files directly inside the server's log directory can be read.
* **Uploads**: application files are stored in `${server.config.dir}/apps` (no path traversal), up to 1 GB.
* Bundle internals (`/com/*`, `/OSGI-INF/*`, `/META-INF/*`, `*.class`) are never served.

## Things to consider

1. **Readers can read every configuration file**, including `server.env` and any passwords in
   server.xml. Only put people in `<reader-role>` who may see them. Use encoded or encrypted
   passwords (`bin/securityUtility encode --encoding=aes`) and variables.
2. Administrators can change the server configuration and deploy code: treat the role like shell access.
3. Enabling the feature also activates Liberty's REST handler framework at `/ibm/api/`, secured by the same
   roles (as it is with Admin Center).
4. The console doesn't need `<mpMetrics authentication="false"/>`. It reads `/metrics` with the user's
   sign-in and gets per-application metrics from Liberty's monitoring MBeans, so `/metrics` can stay protected.
5. Consider rate limiting or account lockout in your registry, and audit logging (`audit-1.0` / `audit-2.0`).
