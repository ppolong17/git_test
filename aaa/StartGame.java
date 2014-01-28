package sr.handler;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

import org.apache.log4j.Level;

import sr.Const;
import sr.EventMaster;
import sr.Log;
import sr.MasterData;
import sr.MasterData.ExoUserDtl;
import sr.Misc;
import sr.Parameters;
import sr.Query;
import sr.RedisClient;
import sr.Result;
import sr.Session;

public class StartGame implements Handler 
{
    @Override
    public String process(Long usn, JSONObject user, Parameters request, HttpServletResponse response) 
    {               
        JSONObject session = (JSONObject)request.getAttribute("info");
        
        if(session == null)
        {
            return Result.failure("session-expired");
        }
        
		int dbID = Integer.parseInt(session.get("userdbid").toString());
        if (Misc.isCheckDuplicateCharacter(usn, dbID, Misc.toLong(user.get("current_csn")), Misc.toLong(user.get("slot2_csn")), Misc.toLong(user.get("slot3_csn")) ))       
        	return Result.failure("duplicate_character");
        
        //스테이지 읽기
        String stage_ = request.getParameter("stage");
        
        if (stage_ == null)
            stage_ = "0";
            
        int stage = Integer.parseInt(stage_);
        
        if(stage == 0)
        {
            return Result.failureRefresh("startgame-not-enough-money", user);
        }
        
        // 스테이지 ID가 90106 ~ 90108 일때 설날이벤트 기간이 아니거나
        // 설날이벤트 기능의 DISABLED 되어 있으면 게임 시작을 못하도록 처리
        if(stage >= 90106 && stage <= 90108)
        {
            if(!Misc.is2014LunarNewYear(Misc.toLong(user.get("current_dt"))))
            {
                return Result.failure("startgame-event-disabled");
            }
        }
        
        /*
        // 스테이지 ID가 90103 ~ 90105 일때 크리스마스 기간이 아니거나
        // 크리스마스 기능의 DISABLED 되어 있으면 게임 시작을 못하도록 처리
        if(stage >= 90103 && stage <= 90105)
        {
            if(!Misc.is2013Xmas(Misc.toLong(user.get("current_dt"))))
            {
                return Result.failure("startgame-event-disabled");
            }
        }
        */
        
        
        JSONArray missionList = new JSONArray();
        
        // 이미 게임키가 있으면 해당 키를 게임키로 설정
        String currentGameKey = "";
        JSONObject sessionInfo = (JSONObject) request.getAttribute("info");
        JSONObject game = sessionInfo.get("game")!=null?(JSONObject) sessionInfo.get("game"):null;
        
        if(game != null)
        {
            currentGameKey = game.get("gamekey")!=null?(String) game.get("gamekey"):"";
        }
        
        // 마지막 게임 시작 시각과 현재 시각을 비교해서 짧으면 가짜 키를 생성해서 보내준다
        int gameStartDT = Integer.parseInt(user.get("game_start_dt").toString());
        int currentDT = Integer.parseInt(user.get("current_dt").toString());
        
        if(currentDT - gameStartDT < 2)
        {
            Log.log(Level.ERROR, "too-many-game-start-request usn=%d sid=%s", usn, request.getParameter("sid"));
//                      return Result.failure("too-many-game-start-request");
//                      //게임 키 발급
//                      Random r = new Random();
//                      int gameKey_ = r.nextInt();
//                      String gameKey = String.format("game%08x", gameKey_);
//                      
//                      
//                      //세션 업데이트
//                      JSONObject sessionInfo = (JSONObject) request.getAttribute("info");
//                      JSONObject game = new JSONObject();
//                      game.put("gamekey", gameKey);
//                      game.put("stage", 0);
//                      sessionInfo.put("game", game);
//                      
//                      if(Config.getInt("session-db-type", 0) == 1)
//                              Session.updateSID(request.getParameter("sid"), sessionInfo);
//                      else
//                              Session.update(request.getParameter("sid"), sessionInfo);
            
            JSONObject result = new JSONObject();
            result.put("now", user.get("current_dt"));
            result.put("life", user.get("life"));
            result.put("lifecharge", user.get("charge_dt"));
            result.put("gamekey", currentGameKey);
            result.put("survival_life", user.get("survival_life"));
            result.put("survival_charge_dt", user.get("survival_charge_dt"));
            result.put("coin", user.get("coin"));
            result.put("cash", user.get("cash"));
            result.put("friendship_pnt", user.get("friendship_pnt"));
            result.put("mission_list", new JSONArray());
            result.put("event_coin", 0);
            result.put("event_exp", 0);
            
            game = null;
            
            return Result.success(result);
        }
//              Log.log(Level.INFO, "start game usn=%d sid=%s", usn, request.getParameter("sid"));
                // 로그 남기기 위한 스트링들
//              String playMode = "";
//              int useLife = 0;
//              int useSurvivalLife = 0;
//              int usePVPLife = 0;
        
        // 이벤트 체크
        // 모든 던전 이벤트 확인
        long now = Misc.toLong(user.get("current_dt"));
        List<EventMaster.Dungeon> eventList = new ArrayList<EventMaster.Dungeon>();
        List<EventMaster.Dungeon> allDungeonEventList = EventMaster.dungeonMap.get(0);
        
        if(allDungeonEventList != null)
            eventList.addAll(allDungeonEventList);
            
        // 테마 확인
        int theme = stage - (stage % 10000);
        
        List<EventMaster.Dungeon> themeEventList = EventMaster.dungeonMap.get(theme);
        
        if(themeEventList != null)
            eventList.addAll(themeEventList);
            
        // 던전 확인
        List<EventMaster.Dungeon> dungeonEventList = EventMaster.dungeonMap.get(stage);
        
        if(dungeonEventList != null)
            eventList.addAll(dungeonEventList);
            
        int eventExp = 0;
        int eventCoin = 0;
        int eventLife = 0;
        int len = eventList.size();
        
        for(int i = 0; i < len; ++i)
        {
            EventMaster.Dungeon event = eventList.get(i);
            
            if(event.startDT < now && now < event.endDT)
            {
                switch(event.eventType)
                {
                    case 1: // 경험치 증가
                        eventExp = event.eventValue;
                        break;
                        
                    case 2: // 코인 획득 증가
                        eventCoin = event.eventValue;
                        break;
                        
                    case 5: // 열쇠 소모 없음
                        eventLife = 1;
                        break;
                }
            }
        }
        
        
        
        // 이벤트 던전 확인
        if ( stage >= 90106 && stage <= 90108 ) //2014 설날 이벤트 던전
        {
            int playLimit = MasterData.getConfInt("PLAY_LIMIT_LUNARNEWYEAR", -1);
            
            if(playLimit >= 0)
            {
                // 열쇠 소모 없음
                eventLife = 1;
                
                // 입장 제한 체크
                JSONObject MorningData = Query.select1st(dbID, "P_GET_ONE_DUNGEON_CLEAR_TIME", usn, 90106);
                JSONObject AfternoonData = Query.select1st(dbID, "P_GET_ONE_DUNGEON_CLEAR_TIME", usn, 90107);
                JSONObject NightData = Query.select1st(dbID, "P_GET_ONE_DUNGEON_CLEAR_TIME", usn, 90108);
                
                int nMorningPlayCount = 0;
                int nAfternoonPlayCount = 0;
                int nNightPlayCount = 0;
                
                long today0am = Misc.toLong(user.get("today0am"));
                
                long updateDt;
                
                // 새해 아침
                if ( MorningData != null && !MorningData.isEmpty() )
                {
                	updateDt = Misc.toLong(MorningData.get("update_dt"));
                	if ( today0am < updateDt ) // 오늘 플레이했으면 플레이 횟수를 가져온다!
                		nMorningPlayCount = (int) MorningData.get("play_cnt");
                }
                
                // 새해 오후
                if ( AfternoonData != null && !AfternoonData.isEmpty() )
                {
                	updateDt = Misc.toLong(AfternoonData.get("update_dt"));
                	if ( today0am < updateDt ) // 오늘 플레이했으면 플레이 횟수를 가져온다!
                		nAfternoonPlayCount = (int) AfternoonData.get("play_cnt");
                }
                
                // 새해 밤
    	        if ( NightData != null && !NightData.isEmpty() )
    	        {
    	        	updateDt = Misc.toLong(NightData.get("update_dt"));
    	        	if ( today0am < updateDt ) // 오늘 플레이했으면 플레이 횟수를 가져온다!
    	        		nNightPlayCount = (int) NightData.get("play_cnt");
    	        }
    	        
    	        // 총 플레이한 횟수
    	        int nTotalPlayCount = nMorningPlayCount+nAfternoonPlayCount+nNightPlayCount;
    	        if ( nTotalPlayCount >= playLimit )
    	        	 return Result.failure("startgame-play-limit");
            }
        }
        else
        {
        	String confKey = String.format("PLAY_LIMIT_%d", stage);

            int playLimit = MasterData.getConfInt(confKey, -1);
            
            if(playLimit >= 0)
            {
                // 열쇠 소모 없음
                eventLife = 1;
                
                // 입장 제한 체크
                JSONObject eventDungeonRs = Query.select1st(dbID, "P_GET_ONE_DUNGEON_CLEAR_TIME", usn, stage);
                
                if(eventDungeonRs != null && eventDungeonRs.isEmpty() == false)
                {
                    int playedCnt = (int)eventDungeonRs.get("play_cnt");
                    
                    if((long)eventDungeonRs.get("update_dt") < Misc.toLong(user.get("today0am")))
                    {
                        playedCnt = 0;
                    }
                    
                    if(playedCnt >= playLimit)
                    {
                        return Result.failure("startgame-play-limit");
                    }
                }
            }
        }
        
        int randomItemID = 0;
        
        String p = request.getParameter("randomitemid");
        
        if(p != null && p.isEmpty() == false)
            randomItemID = Integer.parseInt(p);
            
        // 게임 아이템을 선택했으면 재화가 충분한지 확인
        String[] items = request.getParameterValues("itemid");
        
        int needCoin = 0;
        int needCash = 0;
        int needPnt = 0;
        int balanceCoin = Misc.toInt(user.get("coin"));
        int balanceCash = Misc.toInt(user.get("cash"));
        int balancePnt = Misc.toInt(user.get("friendship_pnt"));
        
        if (items != null)
        {
            for (int i = 0; i < items.length; ++i)
            {
                int itemID = Integer.parseInt(items[i]);
                
                MasterData.GameItem item = MasterData.gameItemMap.get(itemID);
                
                if(item == null)
                {
                    return Result.failure("startgame-no-exist-item");
                }
                
                needCoin += item.priceCoin;
                needCash += item.priceCash;
                needPnt += item.pricePnt;
            }
            
            if(MasterData.getConfInt("STRESS_TEST", 0) == 1)
            {
                needCoin = 0;
                needCash = 0;
                needPnt = 0;
            }
            
            if(balanceCoin < needCoin || balanceCash < needCash || balancePnt < needPnt)
            {
                return Result.failureRefresh("startgame-not-enough-money", user);
            }
            
            balanceCoin -= needCoin;
            balanceCash -= needCash;
            balancePnt -= needPnt;
        }
                
//              JSONArray myMissions = Query.select(dbID, "P_GET_MISSION_LIST", usn);
        
        JSONArray myMissions = Misc.getMissionList(dbID, usn, user);
        
        // 일회용 아이템 가지고 게임 시작하기 미션 체크
        if(items != null || randomItemID > 0)
        {
            JSONArray mission = Misc.checkMission(request, user, Const.MISSION_TYPE_DUNGEON_USE_ITEM, myMissions);
            
            if(mission != null && mission.isEmpty() == false)
                missionList.addAll(mission);
        }
        
        JSONObject result = new JSONObject();
        
        //라이프 갱신 후 확인
        Misc.lifeCare(user);
        
        int life = Misc.toInt(user.get("life"));
        
        long lifeCharge = Misc.toLong(user.get("charge_dt"));
        
        // 무한 대전용 목숨 확인
        Misc.survivalLifeCare(user);
        
        int survivalLife = Misc.toInt(user.get("survival_life"));
        
        long survivalCharge = Misc.toLong(user.get("survival_charge_dt"));
        
        if(stage > 10000)
        {
//                      playMode = "D";
            if(MasterData.getConfInt("STRESS_TEST",0) == 0)
            {
                if (life <= 0 && eventLife == 0)
                    return Result.failureRefresh("startgame-no-life", user);
            }
            
            //System.out.println( eventLife );
            
            //깎고 다시 갱신
            if(eventLife == 0)
            {
                if(MasterData.getConfInt("STRESS_TEST", 0) == 0)
                {
                    user.put("life", life - 1);
//                                      useLife = 1;
                }
            }
            
            //System.out.println( eventLife );
            
            Misc.lifeCare(user);
            
            life = Integer.parseInt( user.get("life").toString());
            
            lifeCharge = Misc.toLong(user.get("charge_dt"));
        }
        else
        {
//                      playMode = "S";
            // 무한 대전일 경우
            if(survivalLife <= 0 && eventLife == 0)
            {
                // 목숨이 없다면 캐쉬를 이용해서 입장 가능하다
                if(MasterData.getConfInt("STRESS_TEST",0) == 0)
                {
                    if(balanceCash <= 0)
                        return Result.failureRefresh("startgame-no-life", user);
                }
                
                // 캐쉬 감소
                if(MasterData.getConfInt("STRESS_TEST", 0) == 0)
                {
                    needCash++;
                    balanceCash -= needCash;
                }
            }
            else
            {
                // 무한대전용 목숨을 깎는다
                if(eventLife == 0)
                {
                    if(MasterData.getConfInt("STRESS_TEST", 0) == 0)
                    {
                        survivalLife--;

                        user.put("survival_life", survivalLife);
//                                              useSurvivalLife = 1;
                    }
                }
                
                Misc.survivalLifeCare(user);
                
                survivalCharge = Misc.toLong(user.get("survival_charge_dt"));
            }
            
            // 무한 대전 참가 미션 체크
            JSONArray mission = Misc.checkMission(request, user, Const.MISSION_TYPE_SURVIVAL_PLAY, myMissions);
            
            if(mission != null && mission.isEmpty() == false)
            {
                missionList.addAll(mission);
            }
        }
        
        // 선택한 친구
        String fusn_ = request.getParameter("fusn");
        
        if(fusn_ == null || fusn_.isEmpty())
            fusn_ = "0";
            
        long fusn = Long.parseLong(fusn_);
        
        // 내 현재 우정 포인트
        int friendshipPnt = Integer.parseInt(user.get("friendship_pnt").toString());
        
        result.put("friendship_pnt", friendshipPnt);
        
        // 선택한 친구의 대표 캐릭터 얻기
        result.put("friend", null);
        
        if(fusn >= 2000 && fusn <= 2011)
        {
            // EXO USN 일때의 처리
            int coolTime = MasterData.getConfInt("PICKUP_COOLTIME", 43200);
            
            // 친구를 데려간 시각 업데이트
            JSONObject rs = Query.select1st(dbID, "P_UPDATE_PICKUP_DT2", usn, fusn, coolTime);
            
            int pickupRet = Integer.parseInt(rs.get("ret").toString());
            
            if(pickupRet == 2)
            {
                // 친구 데려가기 쿨타임에 걸렸으면 게임 시작 안함
                return Result.failure("startgame-pickup-cooltime");
            }
            
            if(pickupRet == 1)
            {
                // 나에게도 우정 포인트를 준다
                int friendshipRecvCnt = Integer.parseInt(user.get("friendship_recv_cnt").toString());
                
                friendshipPnt += 10;
                
                if(friendshipPnt > 1000)
                    friendshipPnt = 1000;
                    
                balancePnt = friendshipPnt;
                
                Query.update(dbID, "P_UPDATE_USER_DTL_FRIENDSHIP", usn, friendshipRecvCnt, friendshipPnt);
                
                result.put("friendship_pnt", friendshipPnt);
            }
            
            // 친구 대표 캐릭터의 상세 정보를 알아야한다
            ExoUserDtl exo = MasterData.exoUserDtlMap.get(fusn);
            
            JSONObject friend = new JSONObject();
            friend.put("ctype", exo.cType);
            friend.put("lv", exo.lv);
            friend.put("exp", 0);
            friend.put("enchant_lv", 0);
            friend.put("grade", 0);
            friend.put("weapon_isn", 0);
            friend.put("armor_isn", 0);
            friend.put("necklace_isn", 0);
            friend.put("ring_isn", 0);

            result.put("friend", friend);
            
            int nextResetTime = (int)now + coolTime;                    
            
            result.put("pickup_reset_dt", nextResetTime);
            
            // EXO 친구 데려가기 미션 체크
            JSONArray exoDailyMission = Misc.checkMissionByCond1(request, user, Const.MISSION_TYPE_EXO_FRIEND_HELP1, 12, myMissions);            
            
            if(exoDailyMission != null && exoDailyMission.isEmpty() == false)
            {                
                missionList.addAll(exoDailyMission);
                
                // 일일 미션을 성공했으므로 주간 미션 달성 체크
                JSONArray exoWeeklyMission = Misc.checkMissionByCond1(request, user, Const.MISSION_TYPE_EXO_FRIEND_HELP2, 7, myMissions);
                
                if(exoWeeklyMission != null && exoWeeklyMission.isEmpty() == false)
                    missionList.addAll(exoWeeklyMission);
            }
             
            // 친구과 같이 하기 미션 체크
            JSONArray mission = Misc.checkMission(request, user, Const.MISSION_TYPE_FRIEND_HELP, myMissions);
            
            if(mission != null && mission.isEmpty() == false)
                missionList.addAll(mission);
        }
        else if(fusn > 0)
        {
            int fDBID = Misc.getUserDBIDByUSN(fusn);
            
            if(fDBID > 0)
            {
                JSONObject fobj = Query.select1st(fDBID, "P_GET_USER_DTL", fusn);
                
                if(fobj != null)
                {
                    long fcsn = Long.parseLong(fobj.get("current_csn").toString());
                    
                    if(fcsn > 0)
                    {
                        // 친구 데려가기 쿨타임
                        int coolTime = MasterData.getConfInt("PICKUP_COOLTIME", 43200);
                        
                        if(MasterData.getConfInt("STRESS_TEST", 0) == 1)
                            coolTime = 10;
                            
                        // 친구를 데려간 시각 업데이트
                        JSONObject rs = Query.select1st(dbID, "P_UPDATE_PICKUP_DT2", usn, fusn, coolTime);
                        
                        int pickupRet = Integer.parseInt(rs.get("ret").toString());
                                                
                        if(pickupRet == 2)
                        {
                            // 친구 데려가기 쿨타임에 걸렸으면 게임 시작 안함
                            return Result.failure("startgame-pickup-cooltime");
                        }
                        
                        if(pickupRet == 1)
                        {
                            // 친구에게 우정포인트를 준다
                            Query.update(fDBID, "P_PICKUP_CHAR", fusn);
                            
                            // 나에게도 우정 포인트를 준다
                            int friendshipRecvCnt = Integer.parseInt(user.get("friendship_recv_cnt").toString());

                            friendshipPnt += 10;
                            
                            if(friendshipPnt > 1000)
                                friendshipPnt = 1000;
                                
                            balancePnt = friendshipPnt;
                            
                            Query.update(dbID, "P_UPDATE_USER_DTL_FRIENDSHIP", usn, friendshipRecvCnt, friendshipPnt);
                            
                            result.put("friendship_pnt", friendshipPnt);
                        }
                        
                        // 친구 대표 캐릭터의 상세 정보를 알아야한다
                        JSONObject friend = Query.select1st(fDBID, "P_GET_CHAR_INFO", fusn, fcsn);
                        
                        if(friend != null)
                        {
                            result.put("friend", friend);

                            int nextResetTime = (int)now + coolTime;
                            
                            result.put("pickup_reset_dt", nextResetTime);
                        }
                    }
                }
                
                // 친구과 같이 하기 미션 체크
                JSONArray mission = Misc.checkMission(request, user, Const.MISSION_TYPE_FRIEND_HELP, myMissions);
                
                if(mission != null && mission.isEmpty() == false)
                    missionList.addAll(mission);
            }
        }
        
        // 파티 보너스 미션 체크
        // 같은 등급으로 2명 이상 캐릭터를 구성했을 때 보너스 효과를 얻는다
        JSONArray selectedChar = Query.select(dbID, "P_GET_SELECTED_CHAR", usn);
        
        Integer [] gradeCnt = new Integer[6];
        
        for(int i = 0; i < 6; i++)
            gradeCnt[i] = 0;
            
        boolean bPartyBonus = false;
        
        len = selectedChar.size();
        
        for(int i = 0; i < len; i++)
        {
            JSONObject ch = (JSONObject)selectedChar.get(i);
            
            int chGrade = (int)ch.get("grade");
            
            if(chGrade <= 6)
            {
                gradeCnt[chGrade-1]++;
                
                if(gradeCnt[chGrade-1] >= 2)
                {
                    bPartyBonus = true;
                    break;
                }
            }
        }
        
        if(bPartyBonus)
        {
            JSONArray mi = Misc.checkMission(request, user, Const.MISSION_TYPE_DUNGEON_PARTY_BONUS, myMissions);
            
            if(mi != null && mi.isEmpty() == false)
                missionList.addAll(mi);
        }

        //게임 키 발급
//              Random r = new Random();
//              int gameKey_ = r.nextInt();
        String gameKey = String.format("game%d-%d", usn, System.currentTimeMillis());
                
                //세션 업데이트
//              JSONObject sessionInfo = (JSONObject) request.getAttribute("info");
//              JSONObject game = new JSONObject();
//              game.put("gamekey", gameKey);
//              game.put("stage", stage);
//              sessionInfo.put("game", game);
//              
//              if(Config.getInt("session-db-type", 0) == 1)
//                      Session.updateSID(request.getParameter("sid"), sessionInfo);
//              else
//                      Session.update(request.getParameter("sid"), sessionInfo);
        
        // 세션 업데이트
        Session.createGameKey(usn, gameKey, stage, 0);
        
        //db 업데이트
        if(stage < 10000)
        {
            Query.update(dbID, "P_SURVIVAL_START2", usn, stage, survivalLife, needCoin, needCash, needPnt, survivalCharge);
        }
        else if (0 == Query.update(dbID, "p_start_game2", usn, life, lifeCharge, needCoin, needCash, needPnt))
            return Result.failure("startgame-dberr-update");
            
        if(needCash > 0)
        {
            Query.update(dbID, "P_WRITE_CASHBOOK", usn, -needCash, "SURVIVAL");
        }
        
        result.put("now", now);
        result.put("life", life);
        result.put("lifecharge", lifeCharge);
        result.put("gamekey", gameKey);
        result.put("survival_life", survivalLife);
        result.put("survival_charge_dt", user.get("survival_charge_dt"));
        result.put("coin", balanceCoin);
        result.put("cash", balanceCash);
        result.put("friendship_pnt", balancePnt);
        result.put("mission_list", missionList);
        result.put("event_coin", eventCoin);
        result.put("event_exp", eventExp);
        
        missionList = null;

        if(MasterData.getConfInt("REDIS_USE", 0) == 1)
        {
            JSONObject g = new JSONObject();
            g.put("gamekey", gameKey);
            g.put("stage", stage);

            JSONObject updatedInfo = new JSONObject();
            updatedInfo.put("life", life);
            updatedInfo.put("charge_dt", lifeCharge);
            updatedInfo.put("survival_life", survivalLife);
            updatedInfo.put("survival_charge_dt", user.get("survival_charge_dt"));
            updatedInfo.put("coin", balanceCoin);
            updatedInfo.put("cash", balanceCash);
            updatedInfo.put("friendship_pnt", balancePnt);
            updatedInfo.put("game_start_dt", user.get("current_dt"));
            updatedInfo.put("random_item_id", 0);
            updatedInfo.put("random_item_dungeon", 0);
            updatedInfo.put("game", g);
            
            RedisClient.updateUserInfo(usn, updatedInfo);
        }
                // 로그 전송
//              JSONObject gameObj = new JSONObject();
//              gameObj.put("playmode", playMode);
//              gameObj.put("dungeonid", stage);
//              gameObj.put("useitems", useItems);
//              gameObj.put("gamekey", gameKey);
//              gameObj.put("uselife", useLife);
//              gameObj.put("mylife", user.get("life"));
//              gameObj.put("usecash", needCash);
//              gameObj.put("mycash", balanceCash);
//              gameObj.put("usesurvivallife", useSurvivalLife);
//              gameObj.put("mysurvivallife", user.get("survival_life"));
//              gameObj.put("usepvplife", usePVPLife);
//              gameObj.put("mypvplife", user.get("pvp_life"));
//              gameObj.put("fusn", fusn);
//              MarbleWorks.sendGameStartLog(request, user.get("kk_id").toString(), user, gameObj);
        
        //Misc.removeServerMission(result);
        
        return Result.success(result);
    }
}