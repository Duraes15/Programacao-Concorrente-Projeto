-module(server).
-export([start/1, handle_client/1]).
-define(MIN_MASSA, 10.0).

% Função para calcular a distância entre dois pontos (X1,Y1) e (X2,Y2)
distancia({X1, Y1}, {X2, Y2}) ->
    math:sqrt(math:pow(X2 - X1, 2) + math:pow(Y2 - Y1, 2)).

start(Gate) ->
    try ets:new(utilizadores, [set, public, named_table])
    catch _:_ -> ok end,

    try ets:new(rankings, [ordered_set, public, named_table, {keypos, 2}])
    catch _:_ -> ok end,

    % NOVO — tabela de sessões ativas
    try ets:new(sessoes_ativas, [set, public, named_table])
    catch _:_ -> ok end,

    {ok, ListenSocket} = gen_tcp:listen(Gate, [binary, {packet, 0}, {active, false}, {reuseaddr, true}]),
    io:format("Servidor ligado na porta ~p. À espera de jogadores...~n", [Gate]),
    register(match_maker, spawn(fun() -> match_maker_loop([], 0) end)),
    accept_loop(ListenSocket).

accept_loop(ListenSocket) ->
    {ok, Socket} = gen_tcp:accept(ListenSocket),
    
    % Processo para o cliente
    Pid = spawn(server, handle_client, [Socket]),
    
    % Damos o controlo do socket ao novo processo
    gen_tcp:controlling_process(Socket, Pid),
    accept_loop(ListenSocket).

handle_client(Socket) ->
    % Mensagem Inicial ao dar Connect
    gen_tcp:send(Socket, <<"\n*** Bem-vindo ao Mini-Jogo Concorrente! ***\nComandos disponiveis:\n  - REGISTER,username,password\n  - LOGIN,username,password\n  - CANCEL,username,password\n">>),
    client_loop(Socket).

% Criamos um loop separado para as mensagens seguintes
client_loop(Socket) ->
    case gen_tcp:recv(Socket, 0) of
        {ok, Data} ->
            CleanData = string:trim(binary_to_list(Data)),
            Tokens = string:tokens(CleanData, ","),

            case Tokens of
                [Cmd | Rest] ->
                    LowerCmd = string:lowercase(Cmd),
                    process_command(Socket, LowerCmd, Rest);
                [] -> 
                    gen_tcp:send(Socket, <<"Erro: Nao enviaste nada.\n">>)
            end,
            gen_tcp:send(Socket, <<"\nEscreve o teu comando: ">>),
            client_loop(Socket);

        % ANTES: {error, closed} -> ...
        % AGORA: apanha qualquer erro de socket
        {error, _Reason} ->
            io:format("Um cliente saiu.\n"),
            ok
    end.

%% --- Lógica de Comandos ---

%% --- Lógica de Comandos Amigável ---

% REGISTER: Criação de conta 
process_command(Socket, "register", [User, Pass]) ->
    case ets:insert_new(utilizadores, {User, Pass}) of
        true -> 
            gen_tcp:send(Socket, [<<"Sucesso: O utilizador '">>, list_to_binary(User), <<"' foi criado!\n">>]);
        false -> 
            gen_tcp:send(Socket, <<"Erro: Esse nome ja existe. Escolhe outro.\n">>)
    end;
process_command(Socket, "register", _) ->
    gen_tcp:send(Socket, <<"Instrucao: Usa o formato: REGISTER,teu_nome,tua_pass\n">>);

% LOGIN: Autenticação de jogador [cite: 15]
process_command(Socket, "login", [User, Pass]) ->
    case ets:lookup(utilizadores, User) of
        [{User, Pass}] ->
            % NOVO — verifica se já há sessão ativa
            case ets:lookup(sessoes_ativas, User) of
                [] ->
                    ets:insert(sessoes_ativas, {User, self()}),
                    gen_tcp:send(Socket, [<<"Sucesso: Ola ">>, list_to_binary(User), <<"!\n">>]),
                    menu_loop(Socket, User);
                _ ->
                    gen_tcp:send(Socket, <<"Erro: Esta conta ja esta em uso.\n">>)
            end;
        _ ->
            gen_tcp:send(Socket, <<"Erro: Credenciais invalidas.\n">>)
    end;

process_command(Socket, "login", _) ->
    gen_tcp:send(Socket, <<"Instrucao: Usa o formato: LOGIN,teu_nome,tua_pass\n">>);

% CANCEL: Cancelamento de registo 
process_command(Socket, "cancel", [User, Pass]) ->
    case ets:lookup(utilizadores, User) of
        [{User, Pass}] -> 
            % Remove o utilizador da tabela ETS
            ets:delete(utilizadores, User),
            gen_tcp:send(Socket, [<<"Sucesso: A conta '">>, list_to_binary(User), <<"' foi removida com exito.\n">>]);
        _ -> 
            gen_tcp:send(Socket, <<"Erro: Nao foi possivel cancelar. Verifica o username e password.\n">>)
    end;
process_command(Socket, "cancel", _) ->
    gen_tcp:send(Socket, <<"Instrucao: Para apagar a tua conta usa: CANCEL,teu_nome,tua_pass\n">>);

% Comando desconhecido
process_command(Socket, _, _) ->
    gen_tcp:send(Socket, <<"Erro: Comando desconhecido.\nComandos disponiveis: REGISTER, LOGIN, CANCEL\n">>).

menu_loop(Socket, User) ->
    gen_tcp:send(Socket, <<"\n--- MENU PRINCIPAL ---\n1. Procurar Partida (Entrar na Fila)\n2. Ver Top Pontuacoes (Rankings)\n3. Logout\nEscolha: ">>),
    
    case gen_tcp:recv(Socket, 0) of
        {ok, Data} ->
            Opcao = string:trim(binary_to_list(Data)),
            case Opcao of
                "1" -> 
                    gen_tcp:send(Socket, <<"A entrar na fila de espera...\n">>),
                    wait_loop(Socket, User); 
                "2" -> 
                    show_rankings(Socket),
                    menu_loop(Socket, User);
                "3" -> 
                    ets:delete(sessoes_ativas, User),
                    gen_tcp:send(Socket, <<"Logout efetuado. Ate a proxima!\n">>),
                    handle_client(Socket);
                _ -> 
                    gen_tcp:send(Socket, <<"Opcao invalida.\n">>),
                    menu_loop(Socket, User)
            end;
        % ANTES: {error, closed} -> ok
        % AGORA: apanha qualquer erro de socket
        {error, _Reason} ->
            ets:delete(sessoes_ativas, User),
            io:format("~s desconectou do menu.~n", [User]),
            ok
    end.

gerar_objetos(0) -> [];
gerar_objetos(N) ->
    % Usamos unique_integer para garantir que nunca há dois objetos com o mesmo ID
    Id = erlang:unique_integer([positive]), 
    X = rand:uniform(1905) * 1.0, 
    Y = rand:uniform(985) * 1.0, 
    Tipo = rand:uniform(2), 
    Tamanho = 10.0 + rand:uniform(20), 
    [{obj, Id, {X, Y}, Tipo, Tamanho} | gerar_objetos(N-1)].

processar_colisoes(Jogadores, Objetos) ->
    {Venenosos, Comestiveis} = lists:partition(fun({obj, _, _, Tipo, _}) -> Tipo == 2 end, Objetos),
    
    {JogadoresAposVenenos, VenenosRestantes} = lists:foldl(fun(Jogador, {AccJ, AccV}) ->
        {NovoJ, NovosV} = verificar_colisao_veneno(Jogador, AccV),
        {[NovoJ | AccJ], NovosV}
    end, {[], Venenosos}, Jogadores),
    
    JogadoresPasso1 = lists:reverse(JogadoresAposVenenos),

    {JogadoresFinais, ComestiveisRestantes} = lists:foldl(fun(Jogador, {AccJ, AccC}) ->
        {NovoJ, NovosC} = verificar_colisao_comestivel(Jogador, AccC),
        {[NovoJ | AccJ], NovosC}
    end, {[], Comestiveis}, JogadoresPasso1),
    
    JogadoresPasso2 = lists:reverse(JogadoresFinais),
    
    NumVenenosMortos = length(Venenosos) - length(VenenosRestantes),
    NumComestiveMortos = length(Comestiveis) - length(ComestiveisRestantes),
    
    NovosVenenos = gerar_objetos_tipo(NumVenenosMortos, 2),
    NovosComestiveis = gerar_objetos_tipo(NumComestiveMortos, 1),
    
    {JogadoresPasso2, VenenosRestantes ++ ComestiveisRestantes ++ NovosVenenos ++ NovosComestiveis}.

verificar_colisao_veneno(Jogador = {_, PosJ, _, _, M, _, _}, Venenosos) ->
    RaioP = math:sqrt(M * 20),
    lists:foldl(fun({obj, IdO, PosO, _, TamO}, {J = {U1, P1, V1, A1, M1, Pid1, Ref1}, AccV}) ->
        Dist = distancia(PosJ, PosO),
        if Dist < (RaioP + TamO) ->
            NovaMassa = max(?MIN_MASSA, M1 - TamO),
            io:format(">> COLISAO: ~s bateu num veneno! Massa: ~.2f -> ~.2f~n", [U1, M1, NovaMassa]),
            NovoJ = {U1, P1, V1, A1, NovaMassa, Pid1, Ref1},
            NovoV = lists:keydelete(IdO, 2, AccV),
            {NovoJ, NovoV};
        true ->
            {J, AccV}
        end
    end, {Jogador, Venenosos}, Venenosos).

verificar_colisao_comestivel(Jogador = {_, PosJ, _, _, M, _, _}, Comestiveis) ->
    RaioP = math:sqrt(M * 20),
    lists:foldl(fun({obj, IdO, PosO, _, TamO}, {J = {U1, P1, V1, A1, M1, Pid1, Ref1}, AccC}) ->
        Dist = distancia(PosJ, PosO),
        if (RaioP > TamO) andalso ((Dist + TamO) =< RaioP) ->
            NovaMassa = M1 + TamO,
            io:format(">> CAPTURA: ~s comeu um verde! Massa: ~.2f -> ~.2f~n", [U1, M1, NovaMassa]),
            NovoJ = {U1, P1, V1, A1, NovaMassa, Pid1, Ref1},
            NovoC = lists:keydelete(IdO, 2, AccC),
            {NovoJ, NovoC};
        true ->
            {J, AccC}
        end
    end, {Jogador, Comestiveis}, Comestiveis).

gerar_objetos_tipo(0, _Tipo) -> [];
gerar_objetos_tipo(N, Tipo) ->
    Id = erlang:unique_integer([positive]),
    X = rand:uniform(1905) * 1.0,
    Y = rand:uniform(985) * 1.0,
    [{obj, Id, {X, Y}, Tipo, 10.0 + rand:uniform(20)} | gerar_objetos_tipo(N-1, Tipo)].

processar_capturas_jogadores(Jogadores) ->
    Sorted = lists:reverse(lists:keysort(5, Jogadores)),
    aplicar_capturas(Sorted, []).

aplicar_capturas([], Processados) -> Processados;
aplicar_capturas([Predador | Resto], Processados) ->
    {U1, Pos1, V1, A1, M1, Pid1, Ref1} = Predador,
    Raio1 = math:sqrt(M1 * 20),
    {NovoResto, GanhoMassa} = engolir_presas(Predador, Raio1, Resto, 0),
    NovoPredador = {U1, Pos1, V1, A1, M1 + GanhoMassa, Pid1, Ref1},
    aplicar_capturas(NovoResto, [NovoPredador | Processados]).

engolir_presas(_Predador, _Raio1, [], GanhoMassa) -> {[], GanhoMassa};
engolir_presas(Predador = {U1, Pos1, _, _, _, _, _}, Raio1, [Presa | Resto], GanhoMassa) ->
    {U2, Pos2, _V2, A2, M2, Pid2, Ref2} = Presa,
    Dist = distancia(Pos1, Pos2),
    Raio2 = math:sqrt(M2 * 20),
    if (Raio1 > Raio2) andalso ((Dist + Raio2) =< Raio1) ->
        MassaRoubada = M2 / 4.0,
        NovaMassaPresa = max(?MIN_MASSA, M2 - MassaRoubada),
        NovaPos2 = {rand:uniform(1905) * 1.0, rand:uniform(985) * 1.0},
        NovaPresa = {U2, NovaPos2, {0.0, 0.0}, A2, NovaMassaPresa, Pid2, Ref2},
        io:format(">> PVP: ~s engoliu ~s!~n", [U1, U2]),
        {FinalResto, FinalGanho} = engolir_presas(Predador, Raio1, Resto, GanhoMassa + MassaRoubada),
        {[NovaPresa | FinalResto], FinalGanho};
    true ->
        {FinalResto, FinalGanho} = engolir_presas(Predador, Raio1, Resto, GanhoMassa),
        {[Presa | FinalResto], FinalGanho}
    end.

show_rankings(Socket) ->
    Lista = ets:tab2list(rankings),
    gen_tcp:send(Socket, <<"RANK_START\n">>), % Avisa o Java para começar a guardar
    case Lista of
        [] -> gen_tcp:send(Socket, <<"Ainda nao ha recordes registados.\n">>);
        _  -> [gen_tcp:send(Socket, [User, <<": ">>, integer_to_binary(Vits), <<" vitorias\n">>]) 
               || {User, Vits} <- Lista]
    end,
    gen_tcp:send(Socket, <<"RANK_END\n">>).

match_maker_loop(Fila, NumPartidasAtivas) ->
    receive
        {entrar_na_fila, Username, Pid} ->
            Ref = monitor(process, Pid),
            % Adicionamos o estado 'false' (não está pronto)
            NovaFila = Fila ++ [{Username, Pid, Ref, false}],
            io:format("DEBUG: ~p entrou na fila. Esperando 'Ready'...~n", [Username]),
            match_maker_loop(NovaFila, NumPartidasAtivas);

        {ready, Pid} ->
            % Quando o Java envia "READY", atualizamos o estado na lista
            NovaFila = lists:map(fun({U, P, R, S}) -> 
                if P == Pid -> {U, P, R, true}; true -> {U, P, R, S} end 
            end, Fila),
            
            % Verificar se as condições de início foram atingidas
            verificar_inicio_partida(NovaFila, NumPartidasAtivas);

        {'DOWN', Ref, process, _Pid, _Reason} ->
            NovaFila = lists:keydelete(Ref, 3, Fila),
            match_maker_loop(NovaFila, NumPartidasAtivas);

        {partida_terminou} ->
            match_maker_loop(Fila, NumPartidasAtivas - 1)
    end.

verificar_inicio_partida(Fila, NumPartidasAtivas) ->
    Prontos = [P || P <- Fila, element(4, P) == true],
    TotalNaFila = length(Fila),
    NumProntos = length(Prontos),

    % Lógica: 
    % 1. Se chegarmos a 4 e todos derem Ready -> Começa com 4.
    % 2. Se tivermos 3, todos derem Ready e não houver um 4º a entrar -> Começa com 3.
    if 
        NumProntos == 4, NumPartidasAtivas < 4 ->
            iniciar_jogo_com_n(Fila, 4, NumPartidasAtivas);
        NumProntos == 3, TotalNaFila == 3, NumPartidasAtivas < 4 ->
            iniciar_jogo_com_n(Fila, 3, NumPartidasAtivas);
        true -> 
            match_maker_loop(Fila, NumPartidasAtivas)
    end.

iniciar_jogo_com_n(Fila, N, NumPartidasAtivas) ->
    {Jogadores, Resto} = lists:split(N, Fila),
    % Limpamos o campo 'ReadyStatus' para a função inicializar_jogo não se baralhar
    JogadoresLimpos = [{U, P, R} || {U, P, R, _S} <- Jogadores],
    spawn(fun() -> inicializar_jogo(JogadoresLimpos, self()) end),
    io:format("Partida iniciada com ~p jogadores prontos!~n", [N]),
    match_maker_loop(Resto, NumPartidasAtivas + 1).

wait_loop(Socket, User) ->
    % 1. Entra na fila apenas UMA vez
    match_maker ! {entrar_na_fila, User, self()},
    
    % 2. Ativa o modo de receção de eventos do Socket
    inet:setopts(Socket, [{active, true}]),
    
    % 3. Salta para a função que apenas espera pelas mensagens
    wait_loop_atento(Socket, User).

wait_loop_atento(Socket, User) ->
    receive
        % wait_loop_atento
        {tcp_closed, _Socket} ->
            ets:delete(sessoes_ativas, User),
            io:format("~s fechou a aplicação enquanto estava na fila.~n", [User]),
            exit(done);
        {tcp, Socket, Data} ->
            case string:trim(binary_to_list(Data)) of
                "READY" -> 
                    match_maker ! {ready, self()},
                    gen_tcp:send(Socket, <<"Estado: Estás pronto! A aguardar outros...\n">>),
                    wait_loop_atento(Socket, User);
                _ -> 
                    wait_loop_atento(Socket, User)
            end;
        % Capturamos o MestrePid aqui (antigo _Dados)
        {começar_partida, MestrePid} ->
            inet:setopts(Socket, [{active, true}]),
            gen_tcp:send(Socket, <<"A partida vai comecar!\n">>),
            % Chamamos a função com 3 argumentos para bater certo com a definição
            game_loop(Socket, User, MestrePid)

        after 30000 -> 
            gen_tcp:send(Socket, <<"Ainda estas na fila. A aguardar jogadores...\n">>),
            wait_loop_atento(Socket, User)
    end.

% Comentei a função abaixo para não dar warnings
% Função auxiliar para continuar a espera sem reenviar mensagem ao match_maker
%wait_loop_cont(Socket, User) ->
%    receive
%        {começar_partida, _} -> ok % Lógica de jogo
%    after 30000 -> 
%        show_rankings(Socket),
%        wait_loop_cont(Socket, User)
%    end.

% --- ATUALIZAÇÃO: INICIALIZAR JOGO ---
inicializar_jogo(ListaJogadores, MatchMakerPid) ->
    EstadoInicial = [
        begin
            Ref = monitor (process, Pid),
            {User, {100.0, 100.0 + (I*50.0)}, {0.0, 0.0}, 0.0, 50.0, Pid, Ref}
        end
        || {I, {User, Pid, _Ref}} <- lists:zip(lists:seq(1, length(ListaJogadores)), ListaJogadores) ],

    % Criamos 10 objetos no mapa logo no início
    ObjetosIniciais = gerar_objetos(30),

    [Pid ! {começar_partida, self()} || {_User, Pid, _Ref} <- ListaJogadores],
    self() ! tick, 
    % Passamos os Jogadores e os Objetos separadamente
    partida_loop(EstadoInicial, ObjetosIniciais, MatchMakerPid).

% --- ATUALIZAÇÃO: PARTIDA LOOP (Agora recebe Objetos) ---
partida_loop(Jogadores, Objetos, MatchMakerPid) ->
    receive
        {mover, User, Tecla} ->
            case Tecla of 
                "ESC" ->
                    JogadoresSaiu = lists:filter(fun({U, _Pos, _Vel, _Ang, _M, _P, _R}) -> User == U end, Jogadores),
                    NovosJogadores = lists:filter(fun({U, _Pos, _Vel, _Ang, _M, _P, _R}) -> User =/= U end, Jogadores),

                    broadcast_saiu(JogadoresSaiu),

                    % Agora passamos os Objetos para o check_winner
                    check_winner(NovosJogadores, Objetos, MatchMakerPid);
                    
                _ ->
                    NovoJogador = calcular_fisica(User, Tecla, Jogadores),
                    NovosJogadores = lists:keyreplace(User, 1, Jogadores, NovoJogador),
                    partida_loop(NovosJogadores, Objetos, MatchMakerPid)
                end;

        tick ->
            JogadoresComInercia = aplicar_movimento_global(Jogadores),
            
            {JogadoresAposObjetos, NovosObjetos} = processar_colisoes(JogadoresComInercia, Objetos),

            NovosJogadores = processar_capturas_jogadores(JogadoresAposObjetos),

            broadcast_estado(NovosJogadores, NovosObjetos),
            
            erlang:send_after(30, self(), tick),
            partida_loop(NovosJogadores, NovosObjetos, MatchMakerPid);

        % Mudei o _Pid para _PidDown para resolver o warning
        {'DOWN', Ref, process, _PidDown, _Reason} ->
            NovosJogadores = lists:filter(fun({_User, _Pos, _Vel, _Ang, _M, _P, R}) -> R =/= Ref end, Jogadores),
            
            % Agora passamos os Objetos para o check_winner
            check_winner(NovosJogadores, Objetos, MatchMakerPid);

        {fim_de_jogo} ->
            MatchMakerPid ! {partida_terminou}
    end.


% Constantes da física
-define(FORCA, 10.0).   % era 5.0 — muito fraco
-define(TORQUE, 0.2).  % era 0.1
-define(ATRITO, 0.995). % era 0.98 — travava em ~1 segundo

% --- ATUALIZAÇÃO: CHECK WINNER AGORA RECEBE OBJETOS ---
check_winner(Jogadores, Objetos, MatchMakerPid) ->
    case length(Jogadores) of
        1 -> 
            [Winner] = Jogadores, 
            {UserWinner, _, _, _, _, _, _} = Winner,
            io:format("Vencedor: ~p~n", [UserWinner]),

            broadcast_fim(Jogadores),

            MatchMakerPid ! {partida_terminou};
        _ ->
            partida_loop(Jogadores, Objetos, MatchMakerPid)
    end.

calcular_fisica(User, Comando, Estado) ->
    {User, {X, Y}, {VX, VY}, Angulo, Massa, Pid, Ref} = lists:keyfind(User, 1, Estado),
    
    case Comando of
        "UP" -> 
            % Acelera na direção do ângulo atual (F = m * a -> a = F/m)
            AX = math:cos(Angulo) * (?FORCA / Massa),
            AY = math:sin(Angulo) * (?FORCA / Massa),
            {User, {X, Y}, {VX + AX, VY + AY}, Angulo, Massa, Pid, Ref};
        "LEFT" -> 
            {User, {X, Y}, {VX, VY}, Angulo - ?TORQUE, Massa, Pid, Ref};
        "RIGHT" -> 
            {User, {X, Y}, {VX, VY}, Angulo + ?TORQUE, Massa, Pid, Ref};
        _ -> 
            {User, {X, Y}, {VX, VY}, Angulo, Massa, Pid, Ref}
    end.

% Esta função corre para TODOS os jogadores a cada 30ms
aplicar_movimento_global(Estado) ->
    lists:map(fun({U, {X, Y}, {VX, VY}, Ang, M, Pid, Ref}) ->
        NVX = VX * ?ATRITO,
        NVY = VY * ?ATRITO,

        NX = max(0.0, min(1905.0, X + NVX)),
        NY = max(0.0, min(985.0, Y + NVY)),

        FVX = if NX == 0.0; NX == 1905.0 -> 0.0; true -> NVX end,
        FVY = if NY == 0.0; NY == 985.0 -> 0.0; true -> NVY end,

        {U, {NX, NY}, {FVX, FVY}, Ang, M, Pid, Ref}
    end, Estado).

% Funções auxiliares simples para Erlang não se queixar
%cos(A) -> math:cos(A).
%sin(A) -> math:sin(A).
% Envia o estado para o processo de cada jogador
% --- ATUALIZAÇÃO: BROADCAST ESTADO (Agora envia P: e O:) ---
broadcast_estado(Jogadores, Objetos) ->
    MsgJogadores = lists:foldl(fun({U, {X, Y}, _, Ang, M, _Pid, _}, Acc) -> 
        JogadorData = io_lib:format(",P:~s:~.2f:~.2f:~.2f:~.2f", 
                                    [U, float(X), float(Y), float(Ang), float(M)]),
        Acc ++ lists:flatten(JogadorData)
    end, "DATA", Jogadores),
    
    MsgCompleta = lists:foldl(fun({obj, Id, {X, Y}, Tipo, Tam}, Acc) ->
        ObjetoData = io_lib:format(",O:~p:~.2f:~.2f:~p:~.2f", 
                                   [Id, float(X), float(Y), Tipo, float(Tam)]),
        Acc ++ lists:flatten(ObjetoData)
    end, MsgJogadores, Objetos),
    
    [Pid ! {actualizar_mundo, MsgCompleta} || {_, _, _, _, _, Pid, _} <- Jogadores].

broadcast_fim(Estado) ->
    [Pid ! {fim_de_jogo} || {_, _, _, _, _, Pid, _} <- Estado].

broadcast_saiu(Estado) ->
    [Pid ! {saiu} || {_, _, _, _, _, Pid, _} <- Estado].
% Exemplo de como o estado do jogador pode ser controlado
% Estado = {X, Y}
game_loop(Socket, User, MestrePid) ->
    receive
        % 1. Recebe tecla do Java e avisa o Mestre
        {tcp, Socket, Data} ->
            Tecla = string:trim(binary_to_list(Data)),
            MestrePid ! {mover, User, Tecla},
            game_loop(Socket, User, MestrePid);

        % 2. Recebe o estado global do Mestre e envia para o Java
        {actualizar_mundo, StringEstado} ->
            gen_tcp:send(Socket, list_to_binary(StringEstado ++ "\n")),
            game_loop(Socket, User, MestrePid);

        {fim_de_jogo} ->
            gen_tcp:send(Socket, <<"FIM\n">>),
            inet:setopts(Socket, [{active, false}]),
            menu_loop(Socket,User),
            ok;
        {saiu} ->
            gen_tcp:send(Socket, <<"FIM\n">>),
            io:format("DESISTÊNCIA: ~s abandonou a partida em curso!~n", [User]),
            ets:delete(sessoes_ativas, User),
            inet:setopts(Socket, [{active, false}]),
            menu_loop(Socket, User);
        % game_loop
        {tcp_closed, _Socket} ->
            ets:delete(sessoes_ativas, User),  % NOVO
            io:format("DESISTÊNCIA: ~s abandonou a partida em curso!~n", [User]),
            exit(done)
    end.