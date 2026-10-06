# Cloudflare Tunnel + DNS para chimaclub.com
# Autenticação: export CLOUDFLARE_API_TOKEN="..." antes de rodar o terraform.

terraform {
  required_version = ">= 1.5" # necessário para os blocos "import"
  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}

provider "cloudflare" {}

# ---------------------------------------------------------------------------
# Variáveis
# ---------------------------------------------------------------------------

variable "account_id" {
  description = "ID da conta Cloudflare (aparece na página inicial do domínio, barra lateral direita)"
  type        = string
}

variable "zone_id" {
  description = "ID da zona chimaclub.com (mesma barra lateral, campo Zone ID)"
  type        = string
}

variable "tunnel_id" {
  description = "UUID do túnel existente (Networking > Tunnels)"
  type        = string
}

variable "tunnel_name" {
  description = "Nome exato do túnel como aparece no painel"
  type        = string
  default     = "meusite"
}

variable "origin" {
  description = "Endereço do container no host"
  type        = string
  default     = "http://127.0.0.1:8082"
}

variable "hostnames" {
  description = "Hostnames publicados pelo túnel"
  type        = list(string)
  default     = ["chimaclub.com", "www.chimaclub.com"]
}

# IDs dos registros CNAME que o painel já criou (para importar).
# Deixe vazio ({}) se você apagou esses registros e quer que o Terraform crie.
variable "existing_dns_record_ids" {
  description = "Mapa hostname => ID do registro DNS existente"
  type        = map(string)
  default     = {}
}

# ---------------------------------------------------------------------------
# Imports: adotam o que já existe, sem recriar
# ---------------------------------------------------------------------------

import {
  to = cloudflare_zero_trust_tunnel_cloudflared.site
  id = "${var.account_id}/${var.tunnel_id}"
}

import {
  to = cloudflare_zero_trust_tunnel_cloudflared_config.site
  id = "${var.account_id}/${var.tunnel_id}"
}

import {
  for_each = var.existing_dns_record_ids
  to       = cloudflare_dns_record.tunnel[each.key]
  id       = "${var.zone_id}/${each.value}"
}

# ---------------------------------------------------------------------------
# Túnel
# ---------------------------------------------------------------------------

resource "cloudflare_zero_trust_tunnel_cloudflared" "site" {
  account_id = var.account_id
  name       = var.tunnel_name
  config_src = "cloudflare" # rotas gerenciadas remotamente (sem config.yml local)

  lifecycle {
    prevent_destroy = true # evita apagar o túnel por engano
  }
}

# Rotas (equivale às "Published applications" do painel)
resource "cloudflare_zero_trust_tunnel_cloudflared_config" "site" {
  account_id = var.account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.site.id

  config = {
    ingress = concat(
      [for h in var.hostnames : {
        hostname = h
        service  = var.origin
      }],
      # regra final obrigatória: qualquer outro hostname recebe 404
      [{ service = "http_status:404" }]
    )
  }
}

# ---------------------------------------------------------------------------
# DNS: um CNAME por hostname apontando para o túnel
# ---------------------------------------------------------------------------

resource "cloudflare_dns_record" "tunnel" {
  for_each = toset(var.hostnames)

  zone_id = var.zone_id
  name    = each.value
  type    = "CNAME"
  content = "${cloudflare_zero_trust_tunnel_cloudflared.site.id}.cfargotunnel.com"
  proxied = true
  ttl     = 1 # automático (obrigatório quando proxied = true)
  comment = "Gerenciado pelo Terraform - Cloudflare Tunnel"
}

# ---------------------------------------------------------------------------
# Saídas
# ---------------------------------------------------------------------------

output "tunnel_cname_target" {
  value = "${cloudflare_zero_trust_tunnel_cloudflared.site.id}.cfargotunnel.com"
}
