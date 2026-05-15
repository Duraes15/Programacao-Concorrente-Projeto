<div style="text-align: center; margin-top: 100px;">

# Relatório de Trabalho Prático - Grupo 2
## Programação Concorrente

Universidade do Minho <br>
15 de Maio de 2026

<br><br><br>

### Membros do Grupo: 
Pedro Miguel Igreja Gomes - a102929 <br>
Fábio Mendes Castelhano - a105728 <br>
Joao Carlos Teixeira Neiva - a108579 <br>
João Álvaro Oliveira Durães - a109065

</div>

<div style="page-break-after: always;"></div>

## 1. Introdução
No âmbito do projeto da UC de Programação Concorrente, do 3º ano de LCC, este relatório tem como objetivo elucidar as principais decisões tomadas na arquitetura Cliente-Servidor do projeto.

De maneira suficientemente detalhada, o grupo tentará explicar as nuances:
1. Da implementação do cliente em Java, por exemplo quais bibliotecas foram utilizadas para tratar da interface gráfica, e qual foi a abordagem para tratar múltiplos clientes. Apesar de sugerida a utilização do procesing, o grupo optou por utilizar outras bibliotecas;
2. Da implementação do servidor em Erlang, nomeadamente quais funções que tratam de cada funcionalidade do jogo, como é feita a troca de informação entre o cliente e o servidor, e qual é o critério para haver troca de informações entre o cliente e o servidor.

## 2. Implementação do Cliente (Java)
O cliente foi desenvolvido para atuar como uma interface de visualização e captura de comandos, delegando toda a lógica física ao servidor. As bibliotecas utilizadas para a interface gráfica foram a javax.swing e a java.awt.

### 2.1. Funcionalidades e Estrutura de Funções
A execução do cliente baseia-se num fluxo de estados gerido pelas seguintes funções principais:
* **Inicialização e Interface:** A função `initGUI()` configura a janela principal em modo maximizado. A navegação entre os diferentes ecrãs (Login, Menu, Jogo, Rankings) é efetuada pelas funções `mudarParaLogin()`, `mudarParaMenu()`, `mudarParaJogo()` e `mudarParaRanking()`, que limpam e reconstroem o contentor principal (`JFrame`).
* **Comunicação de Rede:** A função `conectarServidor()` estabelece a ligação TCP e inicia uma *thread* dedicada para a leitura contínua de mensagens através da função `processarMensagem(String msg)`. Esta última atua como um despachante, interpretando comandos como `Sucesso`, `DATA` (atualização do mundo) ou `RANK_START/END`.
* **Interação e Input:** No `LoginPanel`, a função `enviar(String cmd)` trata do registo e autenticação. Durante a partida, o `GamePanel` utiliza um `KeyAdapter` para detetar teclas premidas e um `inputTimer` que, a cada 30ms, envia os comandos de movimento (`UP`, `LEFT`, `RIGHT`, `ESC`) para o servidor.
* **Renderização do Mundo:** A função `updateWorld(String msg)` processa as strings de dados do servidor, atualizando o mapa local. O desenho gráfico é realizado em `paintComponent(Graphics g)`, que percorre os objetos e jogadores para desenhar os círculos com cores, bordas e linhas de direção conforme as regras do enunciado.

### 2.2. Gestão de Múltiplos Clientes e Concorrência Interna
Embora o cliente seja individual, ele processa e exibe dados de múltiplos jogadores enviados pelo servidor:
* **Estado Local Partilhado:** Todos os jogadores e objetos são armazenados no `gameObjectsMap`. Para evitar conflitos de concorrência entre a *thread* de rede (que escreve os dados) e a *thread* de interface gráfica (que lê os dados para desenhar), este mapa é implementado como um `ConcurrentHashMap`.
* **Diferenciação do "Eu":** O código utiliza a variável `myUsername` para identificar qual dos jogadores recebidos na mensagem `DATA` corresponde ao utilizador local. Isso permite que a função `paintComponent` aplique a cor azul na borda do avatar do próprio jogador e vermelha nos restantes, conforme exigido.

## 3. Implementação do Servidor (Erlang)
O servidor é o motor central do jogo, responsável por manter a consistência do estado global e processar a física de todas as partidas em simultâneo.

### 3.1. Concorrência e Gestão de Clientes
O servidor tira partido do modelo de atores do Erlang para suportar múltiplos utilizadores:
* **Processos por Cliente:** A função `accept_loop/1` aceita novas conexões e faz o `spawn` de um processo individual `handle_client/1` para cada socket. Este processo gere o ciclo de vida do utilizador através de `client_loop/1` e `menu_loop/2`.
* **Armazenamento Partilhado (ETS):** Para que processos independentes acedam a dados comuns, são utilizadas tabelas ETS: `utilizadores` (registo), `rankings` (pontuações) e `sessoes_ativas` (para impedir logins duplicados).

### 3.2. Matchmaking e Simulação de Partida
* **Gestão de Filas:** O processo `match_maker_loop/2` recebe pedidos de entrada na fila e estados de prontidão (`READY`). A função `verificar_inicio_partida/2` monitoriza quando 3 ou 4 jogadores estão prontos, iniciando a simulação via `iniciar_jogo_com_n/3`.
* **Ciclo de Jogo (Game Loop):** A função `partida_loop/3` gere o estado ativo de cada jogo, processando mensagens de movimento e eventos `tick` a cada 30ms.
* **Física e Colisões:** Em cada `tick`, a função `aplicar_movimento_global/1` atualiza as posições com base na inércia e limites do mapa. As colisões são tratadas por `processar_colisoes/2`, que utiliza as funções `verificar_colisao_veneno/2` e `verificar_colisao_comestivel/2` para ajustar a massa dos jogadores. A função `processar_capturas_jogadores/1` gere a mecânica de um jogador comer outro (PVP).
* **Sincronização:** A função `broadcast_estado/2` compila o estado atual de todos os jogadores e objetos numa string formatada e envia-a para os processos de cada jogador, garantindo que todos os clientes vejam o mesmo cenário.

## 4. Conclusão
O desenvolvimento deste projeto permitiu consolidar na prática os fundamentos teóricos de sistemas distribuídos e programação concorrente. A separação clara entre a representação visual e a autoridade da simulação (servidor) demonstrou ser eficaz na prevenção de dessincronizações num ecossistema multijogador em rede. 