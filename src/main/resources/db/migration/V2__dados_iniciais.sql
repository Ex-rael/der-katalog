-- V2__dados_iniciais.sql
INSERT INTO configuracao (chave, valor) VALUES
  ('whatsapp_numero',   '5551989250481'),
  ('whatsapp_exibido',  '(51) 98925-0481'),
  ('whatsapp_mensagem', 'Olá! Gostaria de saber a disponibilidade da {produto}.'),
  ('instagram',         'chimaclub'),
  ('loja_nome',         'Chima Club Artefatos'),
  ('loja_lema',         'Seu tempo de qualidade merece artefatos à altura');

-- UUID v7 fixos. A migração precisa ser determinística, e gen_random_uuid()
-- produziria um v4 diferente em cada base, contrariando a decisão da §3.2.
INSERT INTO categoria (id, nome, slug, ordem) VALUES
  ('01920000-0000-7000-8000-000000000001', 'Cuias em madeira', 'cuias-em-madeira', 1),
  ('01920000-0000-7000-8000-000000000002', 'Cuias em porongo', 'cuias-em-porongo', 2);
