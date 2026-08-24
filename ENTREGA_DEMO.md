# PeritaVision — Roteiro da demonstração

Como ligar app + óculos + backend e mostrar o fluxo completo.

## Antes de tudo: descobrir o IP do PC

O celular não enxerga `localhost` — para ele, `localhost` é o próprio celular.
Precisa do **IP do PC na rede Wi-Fi**. No PowerShell:

```
ipconfig
```

Procure **"Endereço IPv4"** do adaptador Wi-Fi. Vai ser algo como `192.168.0.15`.
Anote — vamos chamar de `SEU_IP`.

> Celular e PC precisam estar na **mesma rede Wi-Fi**.
> Wi-Fi corporativo às vezes bloqueia um aparelho de falar com o outro
> ("isolamento de clientes"). Se travar, use o **roteador de casa** ou o
> **hotspot do celular** (ligue o hotspot no celular e conecte o PC nele).

## 1. Backend

No arquivo `backend/.env`, troque a linha:

```
PUBLIC_BASE_URL=http://SEU_IP:3000
```

Isso é **essencial**: é esse endereço que o backend manda para os óculos como
destino da foto. Se ficar `localhost`, os óculos tentam subir a foto para eles
mesmos e nada chega.

Depois:

```
cd "C:\Users\hbrun\OneDrive - Facilmova\Peritavision\backend"
npm run dev
```

Teste no navegador do **celular**: `http://SEU_IP:3000/health`
Se aparecer `{"ok":true,...}`, a rede está OK. Se não abrir, é firewall ou rede
— resolva isso antes de continuar (veja "Se não conectar" no fim).

## 2. App

Abra o app. No cartão **Backend**, troque o endereço para `http://SEU_IP:3000`.

Ordem na tela:

1. **CONECTAR ÓCULOS** → status fica verde "Conectado"
2. **INICIAR SESSÃO** → o app faz login, resolve o protocolo e abre a sessão
   no backend. Só depois disso a captura libera.
3. **LER CÓDIGO (mock)** → carrega a síntese do caso (Barcode e Atena mockados)
4. **TIRAR FOTO** ou **ATIVAR COMANDO DE VOZ** e falar **"capturar"**
   → os óculos sobem o JPEG direto para o backend, que calcula o SHA-256 e
   sela a cadeia de custódia. O contador "Fotos enviadas" sobe.
5. **FINALIZAR SESSÃO** → o backend monta o laudo

## 3. Mostrar o resultado

No navegador: `http://SEU_IP:3000/painel`

Entre com `0001` / `Senha123456`, clique em **Abrir** no laudo. Aparecem as
seções com a origem de cada trecho marcada por cor — azul veio do Atena, verde
foi escrito pelo perito. A seção 7 (CONCLUSÃO) fica vazia de propósito: por
regra do banco, só o perito escreve a conclusão.

## Plano B (se os óculos falharem na hora)

Em `MainActivity.kt`, linha ~74:

```kotlin
private val TIPO_DISPOSITIVO = GlassesDeviceFactory.Tipo.PHONE
```

O app passa a usar a câmera do celular. Todo o resto do fluxo é idêntico — a
foto é selada localmente **e** enviada ao backend. Vale como demonstração
completa do fluxo, sem depender do Bluetooth.

**Deixe uma build PHONE instalada num segundo aparelho, por garantia.**

## Se não conectar

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| `/health` não abre no celular | Firewall do Windows | Libere a porta 3000, ou desligue o firewall da rede privada durante a demo |
| `/health` abre, mas o app dá erro | Endereço errado no app | Confira se digitou `http://SEU_IP:3000` (com `http://`, sem barra no fim) |
| Foto não chega no backend | `PUBLIC_BASE_URL` ainda em `localhost` | Corrija no `.env` e reinicie o backend |
| "nenhuma sessão aberta" | Faltou o passo 2 | Toque em INICIAR SESSÃO |
| "óculos não conectado" | BLE caiu | Toque em CONECTAR ÓCULOS de novo; Localização (GPS) ligada ajuda |

## Liberar a porta 3000 no firewall

PowerShell **como Administrador**:

```
New-NetFirewallRule -DisplayName "PeritaVision 3000" -Direction Inbound -LocalPort 3000 -Protocol TCP -Action Allow
```
