# TLS certificate for the Docker deployment

Put two files here (or in the folder `FAMS_TLS_DIR` names):

| File | What |
|---|---|
| `fullchain.pem` | the server certificate, followed by any intermediate certificates (PEM) |
| `privkey.pem` | its private key (PEM, unencrypted) |

They must be for the host name in `FAMS_PUBLIC_HOST`. Use a certificate from the college's own certificate authority
(or its wildcard certificate). The folder is mounted read-only into the proxy container; nothing in it is copied into an
image or committed (`.gitignore` excludes `*.pem` and `*.key`).

```bash
sudo chown root:root privkey.pem && sudo chmod 600 privkey.pem     # only root can read the key
sudo chmod 644 fullchain.pem
```

Full instructions, and how to make a throw-away certificate for a trial run, are in `docs/docker-deployment.md`.
